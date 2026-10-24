from pathlib import Path
import subprocess
import os
base=Path(__file__).resolve().parent
r=base/'fdbus';out=base/'build';out.mkdir(exist_ok=True)
clang=os.environ['ANDROID_NDK_CLANG'] # e.g. .../aarch64-linux-android30-clang
subprocess.run([clang,'-I'+str(r/'public'),'-c',str(r/'utils/cJSON/cJSON.c'),'-o',str(out/'cjson.o')],check=True)
sources=[]
for d in ['fdbus','platform/linux','utils','worker']:
 sources+=list((r/d).glob('*.cpp'))
sources+=list((r/'platform/socket').rglob('*.cpp'))
for f in ['platform/CEventFd_eventfd.cpp','security/CApiSecurityConfig.cpp','security/CFdbToken.cpp','security/CFdbusSecurityConfig.cpp','log/CLogProducer.cpp','log/CLogPrinter.cpp','log/CFdbLogCache.cpp']:
 sources.append(r/f)
cmd=[clang+'++','-std=c++11','-O1','-D__LINUX__','-DCONFIG_SOCKET_PEERCRED','-DCONFIG_FDB_NO_RTTI','-DFDB_CONFIG_UDS_ABSTRACT','-fno-rtti','-I'+str(r),'-I'+str(r/'public'),str(base/'telltale_reader.cpp'),*map(str,sources),str(out/'cjson.o'),'-static-libstdc++','-llog','-o',str(base.parent/'src/main/jniLibs/arm64-v8a/libfx11_telltale.so')]
p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True);(out/'build-native.log').write_text(p.stdout);print(p.stdout[-5000:]);raise SystemExit(p.returncode)
