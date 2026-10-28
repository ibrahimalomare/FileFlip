import io, json, subprocess, sys, tempfile, threading, time, unittest, urllib.request, urllib.error, zipfile
from pathlib import Path
from PIL import Image
import server
class ApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(); server.ROOT=Path(cls.temp.name); server.TOKEN='test-token-with-at-least-24-characters'
        cls.http=server.ThreadingHTTPServer(('127.0.0.1',0),server.Handler); cls.thread=threading.Thread(target=cls.http.serve_forever,daemon=True); cls.thread.start(); cls.base=f'http://127.0.0.1:{cls.http.server_port}'
    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown(); cls.http.server_close()
        for j in server.jobs.values(): server.stop(j)
        server.jobs.clear(); cls.temp.cleanup()
    def request(self,path,method='GET',data=None,auth=True):
        headers={'Authorization':'Bearer '+server.TOKEN} if auth else {}
        with urllib.request.urlopen(urllib.request.Request(self.base+path,data=data,method=method,headers=headers),timeout=10) as r: return r.read()
    def payload(self):
        im=io.BytesIO(); Image.new('RGB',(30,30),'blue').save(im,format='PNG'); zbytes=io.BytesIO()
        with zipfile.ZipFile(zbytes,'w') as z:
            z.writestr('input-0.png',im.getvalue()); z.writestr('manifest.json',json.dumps({'files':['input-0.png'],'options':{'target':'jpg'}}))
        return zbytes.getvalue()
    def test_auth_required(self):
        with self.assertRaises(urllib.error.HTTPError) as cm: self.request('/jobs/'+'0'*32,auth=False)
        self.assertEqual(cm.exception.code,401); cm.exception.close()
    def test_full_conversion_download_and_deletion(self):
        key=json.loads(self.request('/jobs','POST',self.payload()))['id']
        for _ in range(100):
            obj=json.loads(self.request('/jobs/'+key))
            if obj['state']!='running': break
            time.sleep(.05)
        self.assertEqual(obj['state'],'done',obj)
        result=self.request('/jobs/'+key+'/result'); self.assertTrue(result.startswith(b'\xff\xd8\xff'))
        
        with server.lock:
            self.assertFalse((server.ROOT/key).exists()); self.assertNotIn(key,server.jobs)
    def test_cancel_kills_process_and_removes_files(self):
        key='a'*32; root=server.ROOT/key; root.mkdir(); (root/'private.txt').write_text('temporary')
        p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'],start_new_session=True)
        server.jobs[key]={'root':root,'process':p,'created':time.time()}
        self.request('/jobs/'+key,'DELETE'); self.assertIsNotNone(p.poll()); self.assertFalse(root.exists())
    def test_invalid_archive_rejected_without_leftovers(self):
        before=set(server.ROOT.iterdir())
        with self.assertRaises(urllib.error.HTTPError) as cm: self.request('/jobs','POST',b'invalid')
        self.assertEqual(cm.exception.code,400); cm.exception.close(); self.assertEqual(set(server.ROOT.iterdir()),before)
if __name__=='__main__': unittest.main()
