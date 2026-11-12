#!/usr/bin/env python3
"""Install pinned local E5 assets. No remote inference is used by the application."""
import hashlib,json,pathlib,urllib.request
REVISION='614241f622f53c4eeff9890bdc4f31cfecc418b3'
ROOT=pathlib.Path(__file__).resolve().parents[1]/'models'/'multilingual-e5-small'
ROOT.mkdir(parents=True,exist_ok=True)
files={}
for remote,local in [('onnx/model.onnx','model.onnx'),('onnx/tokenizer.json','tokenizer.json'),('README.md','MODEL_CARD.md')]:
 path=ROOT/local
 if not path.exists():
  print('Downloading',remote,flush=True)
  temp=path.with_suffix(path.suffix+'.part')
  urllib.request.urlretrieve(f'https://huggingface.co/intfloat/multilingual-e5-small/resolve/{REVISION}/{remote}',temp)
  temp.replace(path)
 files[local]=hashlib.sha256(path.read_bytes()).hexdigest()
(ROOT/'manifest.json').write_text(json.dumps({'model':'intfloat/multilingual-e5-small','revision':REVISION,'license':'MIT','files':files},indent=2)+'\n')
print('Installed',ROOT,flush=True)
