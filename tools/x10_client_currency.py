"""Собрать клиентские DAT двух валют H5 в отдельную папку, без правки оригинала."""
import argparse
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'launcher'))
from l2hub.h5currency import build
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('system',type=Path);parser.add_argument('output',type=Path);parser.add_argument('--language',choices=['ru','en'],default='ru')
    args=parser.parse_args();print(build(args.system,args.output,args.language))
