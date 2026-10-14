#!/usr/bin/env python3
"""Verified asset download used by setup and standalone download recovery."""
import argparse,concurrent.futures,hashlib,json,re,tempfile,time,subprocess
from pathlib import Path
parser=argparse.ArgumentParser(description="Download Minecraft 1.21.1 assets directly from Mojang and verify publisher hashes. No game client or credentials are supplied.")
parser.add_argument('--assets-dir',required=True,type=Path,help='Absolute Prism data/assets directory; close Prism before running')
args=parser.parse_args()
assets=args.assets_dir
if not assets.is_absolute():parser.error('--assets-dir must be absolute')
assets.mkdir(parents=True,exist_ok=True)
def fetch(url):
 with tempfile.TemporaryFile() as output:
  # curl rewinds an output file before retrying a partial transfer.
  subprocess.run(['curl','--fail','--location','--silent','--show-error','--retry','4','--retry-all-errors','--connect-timeout','15','--max-time','600','--output','/proc/self/fd/'+str(output.fileno()),url],pass_fds=(output.fileno(),),check=True)
  output.seek(0)
  return output.read()
def sha(data):return hashlib.sha1(data).hexdigest()
def verify(data,digest,size=None):
 if sha(data)!=digest or (size is not None and len(data)!=size):
  raise ValueError('Publisher hash or size mismatch: '+digest)
manifest=json.loads(fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
version=next(v for v in manifest['versions'] if v['id']=='1.21.1')
raw=fetch(version['url']);verify(raw,version['sha1'])
meta=json.loads(raw);indexmeta=meta['assetIndex'];raw=fetch(indexmeta['url'])
verify(raw,indexmeta['sha1'],indexmeta['size'])
index=json.loads(raw);(assets/'indexes').mkdir(parents=True,exist_ok=True)
index_path=assets/'indexes'/f'{indexmeta["id"]}.json'
index_tmp=index_path.with_suffix('.json.download');index_tmp.write_bytes(raw);index_tmp.replace(index_path)
objects={v['hash']:v['size'] for v in index['objects'].values()}
started=time.time();results={'freshDownloaded':0,'alreadyVerified':0,'total':len(objects),'version':'1.21.1','assetIndexSha1':indexmeta['sha1'],'source':'https://resources.download.minecraft.net','verification':'Mojang version manifest, asset index SHA1, and every object SHA1/size'}
def one(item):
 h,n=item
 if not re.fullmatch('[0-9a-f]{40}',h):raise ValueError('Invalid publisher asset hash')
 p=assets/'objects'/h[:2]/h
 if p.is_file():
  d=p.read_bytes()
  if len(d)==n and sha(d)==h:return 'alreadyVerified'
 d=fetch(f'https://resources.download.minecraft.net/{h[:2]}/{h}')
 verify(d,h,n)
 p.parent.mkdir(parents=True,exist_ok=True);tmp=p.with_suffix('.musecraft-part');tmp.write_bytes(d);tmp.replace(p)
 return 'freshDownloaded'
with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
 pending={pool.submit(one,item) for item in objects.items()}
 completed=0;last_report=0
 while pending:
  done,pending=concurrent.futures.wait(pending,timeout=15,return_when=concurrent.futures.FIRST_COMPLETED)
  for future in done:
   results[future.result()]+=1;completed+=1
  now=time.time()
  if now-last_report>=15 or not pending:
   print(json.dumps({'completed':completed,'total':len(objects),'seconds':round(now-started),'alreadyVerified':results['alreadyVerified'],'downloaded':results['freshDownloaded']}),flush=True)
   last_report=now
results['seconds']=round(time.time()-started);results['complete']=True
(assets/'musecraft-download-proof.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results),flush=True)
