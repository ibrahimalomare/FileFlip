"""Private deployment API. Use TLS reverse proxy; never expose port 8080 directly."""
import hmac, io, json, os, re, shutil, signal, subprocess, sys, tempfile, threading, time, uuid, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from engine import MAX_BYTES, ConversionError
ROOT=Path(os.environ.get('FILEFLIP_TMP','/tmp/fileflip')); ROOT.mkdir(parents=True,exist_ok=True,mode=0o700)
TOKEN=os.environ.get('FILEFLIP_TOKEN','')
TTL=900
jobs={}; lock=threading.RLock()

def stop(job):
    p=job['process']
    try: os.killpg(p.pid,signal.SIGKILL)
    except ProcessLookupError: pass
    if p.poll() is None: p.wait(timeout=5)
    shutil.rmtree(job['root'],ignore_errors=True)

def unpack(data,root):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            entries=z.infolist()
            if len(entries)>21 or sum(i.file_size for i in entries)>MAX_BYTES: raise ConversionError('Upload exceeds 50 MB or 20 files.')
            if any(i.flag_bits&1 for i in entries): raise ConversionError('Encrypted upload archives are not supported.')
            names=[i.filename for i in entries]
            if len(set(names))!=len(names) or 'manifest.json' not in names: raise ConversionError('Invalid upload manifest.')
            manifest=json.loads(z.read('manifest.json'))
            files=manifest.get('files',[])
            if not 1<=len(files)<=20: raise ConversionError('Select 1–20 files.')
            if len(set(files))!=len(files): raise ConversionError('Duplicate input names.')
            for name in files:
                if not re.fullmatch(r'input-[0-9]+\.(pdf|docx?|xlsx?|png|jpg)',name): raise ConversionError('Invalid input name.')
                (root/name).write_bytes(z.read(name))
            (root/'manifest.json').write_text(json.dumps(manifest))
    except (zipfile.BadZipFile,KeyError,ValueError,TypeError): raise ConversionError('Invalid upload archive.')

def sweep():
    while True:
        time.sleep(10)
        with lock:
            for key,job in list(jobs.items()):
                if time.time()-job['created']>TTL:
                    stop(job); del jobs[key]
                elif job['process'].poll() is None and time.time()-job['created']>180:
                    stop(job)
                    job['root'].mkdir(exist_ok=True)
                    (job['root']/'status.json').write_text(json.dumps({'state':'failed','message':'Conversion timed out. Try a smaller file.'}))

class Handler(BaseHTTPRequestHandler):
    server_version='FileFlip'
    def log_message(self,*args): pass # no filenames, credentials, document text, or capability URLs in logs
    def authorized(self):
        if not TOKEN or not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+TOKEN):
            self.reply(401,{'message':'Server authorization failed. Check your access token.'}); return False
        return True
    def reply(self,code,obj):
        data=json.dumps(obj).encode(); self.send_response(code); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.send_header('Cache-Control','no-store'); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        if not self.authorized(): return
        if self.path!='/jobs': self.reply(404,{'message':'Not found'}); return
        try:
            size=int(self.headers.get('Content-Length','0'))
            if not 0<size<=MAX_BYTES: self.reply(413,{'message':'Upload must be between 1 byte and 50 MB.'}); return
            self.connection.settimeout(45)
            data=self.rfile.read(size)
            if len(data)!=size: raise ConversionError('Incomplete upload. Please retry.')
            with lock:
                if sum(j['process'].poll() is None for j in jobs.values())>=2:
                    self.reply(503,{'message':'Server is busy. Please retry shortly.'}); return
                key=uuid.uuid4().hex; root=ROOT/key; root.mkdir(mode=0o700)
                try:
                    unpack(data,root)
                    p=subprocess.Popen([sys.executable,str(Path(__file__).with_name('worker.py')),str(root)],start_new_session=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                except Exception:
                    shutil.rmtree(root,ignore_errors=True); raise
                jobs[key]={'root':root,'process':p,'created':time.time()}
            self.reply(202,{'id':key})
        except ConversionError as e: self.reply(400,{'message':str(e)})
        except (OSError,ValueError): self.reply(400,{'message':'Upload failed. Check storage, file size and network.'})
    def locate(self):
        match=re.fullmatch(r'/jobs/([a-f0-9]{32})(/result)?',self.path)
        if not match or match[1] not in jobs: return None,None
        return match[1],jobs[match[1]]
    def do_GET(self):
        self.connection.settimeout(30)
        if not self.authorized(): return
        with lock:
            key,job=self.locate()
            if not job: self.reply(404,{'message':'Conversion expired or was cancelled.'}); return
            status=job['root']/'status.json'
            obj=json.loads(status.read_text()) if status.exists() else {'state':'running','message':'Starting converter'}
            if job['process'].poll() is not None and obj['state']=='running': obj={'state':'failed','message':'Converter stopped unexpectedly. Try a smaller file.'}
            if not self.path.endswith('/result'): self.reply(200,obj); return
            if obj['state']!='done': self.reply(409,{'message':'Result is not ready.'}); return
            path=job['root']/obj['file']
            try:
                self.send_response(200); self.send_header('Content-Type','application/octet-stream'); self.send_header('Content-Length',str(path.stat().st_size)); self.send_header('Cache-Control','no-store'); self.end_headers()
                with path.open('rb') as f: shutil.copyfileobj(f,self.wfile,256*1024)
            finally:
                # One-shot download: deletes source and result even if the client disconnects.
                stop(job); del jobs[key]
    def do_DELETE(self):
        if not self.authorized(): return
        with lock:
            key,job=self.locate()
            if job: stop(job); del jobs[key]
        self.reply(200,{'state':'cancelled'})

if __name__=='__main__':
    if len(TOKEN)<24: sys.exit('Set FILEFLIP_TOKEN to a random secret of at least 24 characters.')
    # Discard leftovers from a previous process/container lifetime.
    for p in ROOT.iterdir():
        if p.is_dir(): shutil.rmtree(p,ignore_errors=True)
    threading.Thread(target=sweep,daemon=True).start()
    ThreadingHTTPServer((os.environ.get('FILEFLIP_BIND','127.0.0.1'),8080),Handler).serve_forever()
