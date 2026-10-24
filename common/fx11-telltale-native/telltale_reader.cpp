#include <common_base/CBaseClient.h>
#include <common_base/CFdbContext.h>
#include <common_base/CFdbMessage.h>
#include <atomic>
#include <thread>
#include <chrono>
#include <cstdio>
class Reader: public CBaseClient {
public: std::atomic<bool> online{false}; Reader():CBaseClient("fx11-autohold-readonly"){}
protected:
 void onOnline(FdbSessionId_t,bool) override {online=true;puts("ONLINE");fflush(stdout);}
 void onOffline(FdbSessionId_t,bool) override {online=false;puts("OFFLINE");fflush(stdout);}
 void onBroadcast(CBaseJob::Ptr &ref) override {
  auto m=castToMessage<CFdbMessage*>(ref);if(m->code()!=1)return;
  int n=m->getPayloadSize();if(n!=213){puts("INVALID");fflush(stdout);return;}
  printf("FRAME %d ",m->isInitialResponse()?1:0);
  for(int i=0;i<n;i++)printf("%02x",m->getPayloadBuffer()[i]);puts("");fflush(stdout);
 }
 void onGetEvent(CBaseJob::Ptr &ref) override {
  auto m=castToMessage<CFdbMessage*>(ref);int32_t status=0;std::string why;
  if(m->isStatus() && m->decodeStatus(status,why) && status==-21){puts("ALIVE");fflush(stdout);}
  else {puts("OFFLINE");fflush(stdout);online=false;}
 }
 void onStatus(CBaseJob::Ptr &,int32_t code,const char*) override {printf("STATUS %d\n",code);fflush(stdout);}
};
int main(int argc,char**argv){
 if(argc!=2)return 2;FDB_CONTEXT->start();auto c=new Reader();c->connect(argv[1]);
 for(int i=0;i<80&&!c->online;i++)std::this_thread::sleep_for(std::chrono::milliseconds(100));
 if(!c->online){puts("OFFLINE");fflush(stdout);_Exit(3);}
 CFdbMsgSubscribeList list;c->addNotifyGroup(list);if(!c->subscribe(list)){puts("INVALID");fflush(stdout);_Exit(4);}
 while(c->online) {
  std::this_thread::sleep_for(std::chrono::seconds(10));
  if(c->online)c->get(0,nullptr,2000); // framework GET_EVENT cache query, never a business invoke
 }
 c->unsubscribe(list);c->disconnect();puts("OFFLINE");fflush(stdout);_Exit(0);
}
