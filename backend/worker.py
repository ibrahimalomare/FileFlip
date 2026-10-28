import json, os, resource, sys
from pathlib import Path
from engine import convert, ConversionError
root=Path(sys.argv[1])
# Per-job CPU, address space and output caps. Parent enforces wall time and process-group cancellation.
resource.setrlimit(resource.RLIMIT_CPU,(150,150))
resource.setrlimit(resource.RLIMIT_AS,(2*1024**3,2*1024**3))
resource.setrlimit(resource.RLIMIT_FSIZE,(150*1024**2,150*1024**2))
def status(obj):
    temp=root/'status.tmp'; temp.write_text(json.dumps(obj)); os.replace(temp,root/'status.json')
try:
    m=json.loads((root/'manifest.json').read_text())
    result=convert([root/n for n in m['files']],m['options'],root,lambda message:status({'state':'running','message':message}))
    status({'state':'done',**result})
except ConversionError as e: status({'state':'failed','message':str(e)})
except Exception: status({'state':'failed','message':'Invalid, encrypted, oversized or damaged document. Unlock the file and verify it opens before retrying.'})
