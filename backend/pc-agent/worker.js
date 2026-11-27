export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null,{headers:cors()});
    if (!url.pathname.startsWith("/api/")) return new Response("Rumin PC Pairing Relay",{headers:{"content-type":"text/plain"}});
    const id = env.PAIRING.idFromName("rumin-pairing-v1");
    return env.PAIRING.get(id).fetch(request);
  }
};
export class PairingRoom {
  constructor(ctx, env) { this.ctx=ctx; this.env=env; }
  async fetch(request) {
    const url=new URL(request.url), path=url.pathname;
    if (request.method==="POST" && path==="/api/pair/create") {
      const code=String(Math.floor(100000+Math.random()*900000));
      const session=crypto.randomUUID();
      await this.ctx.storage.put("pair:"+code,{session,created:Date.now(),pc:false,phone:false});
      return json({code,session,expiresIn:300});
    }
    if (request.method==="POST" && path==="/api/pair/join") {
      const body=await request.json().catch(()=>({}));
      const code=String(body.code||"");
      const pair=await this.ctx.storage.get("pair:"+code);
      if(!pair || Date.now()-pair.created>300000) return json({error:"code_expired"},410);
      pair.phone=true; await this.ctx.storage.put("pair:"+code,pair);
      await this.ctx.storage.put("session:"+pair.session,{code,phone:true,pc:pair.pc,created:pair.created});
      return json({paired:true,session:pair.session});
    }
    if(request.method==="GET" && path==="/api/pair/status") {
      const session=url.searchParams.get("session");
      const data=await this.ctx.storage.get("session:"+session);
      return data?json({paired:!!data.phone}):json({paired:false},404);
    }
    if(request.method==="POST" && path==="/api/pair/confirm") {
      const body=await request.json().catch(()=>({}));
      const data=await this.ctx.storage.get("session:"+String(body.session||""));
      if(!data) return json({error:"session_not_found"},404);
      data.pc=true; await this.ctx.storage.put("session:"+body.session,data);
      const pair=await this.ctx.storage.get("pair:"+data.code);
      if(pair){pair.pc=true;await this.ctx.storage.put("pair:"+data.code,pair);}
      return json({confirmed:true});
    }
    if(request.method==="POST" && path==="/api/relay/send") {
      const body=await request.json().catch(()=>({}));
      const session=String(body.session||"");
      const data=await this.ctx.storage.get("session:"+session);
      if(!data || !data.pc || !data.phone) return json({error:"not_paired"},403);
      const queue=(await this.ctx.storage.get("queue:"+session))||[];
      queue.push({id:crypto.randomUUID(),from:body.from==="phone"?"phone":"pc",payload:body.payload,at:Date.now()});
      await this.ctx.storage.put("queue:"+session,queue.slice(-100));
      return json({sent:true});
    }
    if(request.method==="GET" && path==="/api/relay/poll") {
      const session=url.searchParams.get("session");
      const data=await this.ctx.storage.get("session:"+session);
      if(!data || !data.pc || !data.phone) return json({error:"not_paired"},403);
      const queue=(await this.ctx.storage.get("queue:"+session))||[];
      const after=Number(url.searchParams.get("after")||0);
      const messages=queue.filter(m=>m.at>after);
      return json({messages});
    }
    return json({error:"not_found"},404);
  }
}
function cors(){return {"access-control-allow-origin":"*","access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type","access-control-max-age":"86400"}}
function json(data,status=200){return new Response(JSON.stringify(data),{status,headers:{...cors(),"content-type":"application/json","cache-control":"no-store"}})}
