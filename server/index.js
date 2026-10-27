'use strict';
const express=require('express');
const helmet=require('helmet');
const {rateLimit}=require('express-rate-limit');
const bcrypt=require('bcryptjs');
const jwt=require('jsonwebtoken');
const crypto=require('node:crypto');
const {Pool}=require('pg');
const fs=require('node:fs');
const path=require('node:path');
const {normalizedPhone,inviteCode,codeHash,validLocation}=require('./helpers');

const DATABASE_URL=process.env.DATABASE_URL;
const JWT_SECRET=process.env.JWT_SECRET;
const INVITE_SECRET=process.env.INVITE_SECRET;
if(!DATABASE_URL || !JWT_SECRET || JWT_SECRET.length<32 || !INVITE_SECRET || INVITE_SECRET.length<32){
  console.error('DATABASE_URL, JWT_SECRET (32+ chars), INVITE_SECRET (32+ chars) required');
  process.exit(1);
}
const pool=new Pool({connectionString:DATABASE_URL,max:10,
  ssl:process.env.DB_SSL==='true'?{rejectUnauthorized:true}:undefined});
const app=express();
app.set('trust proxy',1);
app.disable('x-powered-by');
app.use(helmet());
app.use(express.json({limit:'16kb'}));
app.use('/v1/auth',rateLimit({windowMs:15*60*1000,limit:30,standardHeaders:'draft-7',legacyHeaders:false}));
app.use('/v1/pair/accept',rateLimit({windowMs:15*60*1000,limit:15,standardHeaders:'draft-7',legacyHeaders:false}));
app.get('/health',(_req,res)=>res.json({ok:true,service:'Together Couple Location'}));
const sql=(text,params)=>pool.query(text,params);
const safe=handler=>(req,res,next)=>Promise.resolve(handler(req,res,next)).catch(next);
function publicUser(u){return {name:u.name,phone:u.phone,sharing:!!u.sharing_enabled};}
function sign(id){return jwt.sign({sub:id},JWT_SECRET,{expiresIn:'30d',issuer:'together-location',audience:'together-android'});}
async function requireAuth(req,res,next){
  try{
    const header=req.headers.authorization||'';
    if(!header.startsWith('Bearer ')) return res.status(401).json({error:'লগইন প্রয়োজন'});
    const decoded=jwt.verify(header.slice(7),JWT_SECRET,{issuer:'together-location',audience:'together-android'});
    const found=await sql('SELECT id,name,phone,sharing_enabled FROM users WHERE id=$1',[decoded.sub]);
    if(!found.rows.length) return res.status(401).json({error:'অ্যাকাউন্ট পাওয়া যায়নি'});
    req.me=found.rows[0];next();
  }catch{return res.status(401).json({error:'সেশন শেষ হয়েছে, আবার লগইন করুন'});}
}
async function getCouple(me,db=pool){
  const result=await db.query(`SELECT c.id, u.id AS partner_id,u.name,u.phone,u.sharing_enabled
    FROM couples c JOIN users u ON u.id=(CASE WHEN c.member_a=$1 THEN c.member_b ELSE c.member_a END)
    WHERE c.member_a=$1 OR c.member_b=$1 LIMIT 1`,[me]);
  return result.rows[0]||null;
}
app.post('/v1/auth/register',safe(async(req,res)=>{
  const name=String(req.body.name||'').trim();
  const phone=normalizedPhone(req.body.phone);
  const password=String(req.body.password||'');
  if(name.length<2||name.length>80||!phone||password.length<8||password.length>100)
    return res.status(400).json({error:'সঠিক নাম, বাংলাদেশি মোবাইল নম্বর ও ৮+ অক্ষরের পাসওয়ার্ড দিন'});
  const hash=await bcrypt.hash(password,12);
  try{
    const id=crypto.randomUUID();
    const r=await sql('INSERT INTO users(id,name,phone,password_hash) VALUES($1,$2,$3,$4) RETURNING *',[id,name,phone,hash]);
    res.status(201).json({token:sign(id),user:publicUser(r.rows[0])});
  }catch(e){if(e.code==='23505')return res.status(409).json({error:'এই মোবাইল নম্বর আগে ব্যবহার হয়েছে'});throw e;}
}));
app.post('/v1/auth/login',safe(async(req,res)=>{
  const phone=normalizedPhone(req.body.phone);
  const password=String(req.body.password||'');
  if(!phone||!password)return res.status(400).json({error:'মোবাইল নম্বর ও পাসওয়ার্ড দিন'});
  const r=await sql('SELECT * FROM users WHERE phone=$1',[phone]);
  if(!r.rows.length||!(await bcrypt.compare(password,r.rows[0].password_hash)))
    return res.status(401).json({error:'ভুল নম্বর অথবা পাসওয়ার্ড'});
  res.json({token:sign(r.rows[0].id),user:publicUser(r.rows[0])});
}));
app.use('/v1',requireAuth);
app.get('/v1/me',safe(async(req,res)=>{
  const partner=await getCouple(req.me.id);
  res.json({user:publicUser(req.me),paired:!!partner,partner:partner?{name:partner.name,sharing:partner.sharing_enabled}:null});
}));
app.post('/v1/pair/invite',safe(async(req,res)=>{
  if(await getCouple(req.me.id))return res.status(409).json({error:'আগেই একজনের সঙ্গে যুক্ত আছেন'});
  const code=inviteCode();
  await sql('DELETE FROM invites WHERE owner_id=$1 OR expires_at<NOW()',[req.me.id]);
  await sql(`INSERT INTO invites(code_hash,owner_id,expires_at)
    VALUES($1,$2,NOW()+INTERVAL '10 minutes')`,[codeHash(INVITE_SECRET,code),req.me.id]);
  res.json({code,expiresInSeconds:600});
}));
app.post('/v1/pair/accept',safe(async(req,res)=>{
  const code=String(req.body.code||'').trim().toUpperCase();
  if(!/^[A-HJ-NP-Z2-9]{10}$/.test(code))return res.status(400).json({error:'সঠিক ১০ অক্ষরের কোড দিন'});
  const client=await pool.connect();
  try{
    await client.query('BEGIN');
    // Serializes all pairing operations, preventing one account from being paired twice.
    await client.query("SELECT pg_advisory_xact_lock(67221018)");
    const found=await client.query('SELECT owner_id FROM invites WHERE code_hash=$1 AND expires_at>NOW() FOR UPDATE',[codeHash(INVITE_SECRET,code)]);
    if(!found.rows.length){await client.query('ROLLBACK');return res.status(404).json({error:'কোড ভুল অথবা মেয়াদ শেষ'});}
    const owner=found.rows[0].owner_id;
    if(owner===req.me.id){await client.query('ROLLBACK');return res.status(400).json({error:'নিজের কোড নিজের অ্যাকাউন্টে ব্যবহার করা যাবে না'});}
    const existing=await client.query('SELECT id FROM couples WHERE member_a IN ($1,$2) OR member_b IN ($1,$2) LIMIT 1',[owner,req.me.id]);
    if(existing.rows.length){await client.query('ROLLBACK');return res.status(409).json({error:'একজন ইতিমধ্যে অন্য অ্যাকাউন্টে যুক্ত'});}
    // Never carry previous partner's history into a newly approved relationship.
    await client.query('DELETE FROM locations WHERE user_id IN ($1,$2)',[owner,req.me.id]);
    await client.query('DELETE FROM latest_locations WHERE user_id IN ($1,$2)',[owner,req.me.id]);
    await client.query('UPDATE users SET sharing_enabled=FALSE WHERE id IN ($1,$2)',[owner,req.me.id]);
    await client.query('INSERT INTO couples(id,member_a,member_b) VALUES($1,$2,$3)',[crypto.randomUUID(),owner,req.me.id]);
    await client.query('DELETE FROM invites WHERE owner_id IN ($1,$2)',[owner,req.me.id]);
    await client.query('COMMIT');res.json({ok:true});
  }catch(e){await client.query('ROLLBACK');throw e;}finally{client.release();}
}));
app.post('/v1/pair/disconnect',safe(async(req,res)=>{
  const client=await pool.connect();
  try{
    await client.query('BEGIN');
    await client.query('SELECT pg_advisory_xact_lock(67221018)');
    const p=await getCouple(req.me.id,client);
    const members=p?[req.me.id,p.partner_id]:[req.me.id];
    await client.query('UPDATE users SET sharing_enabled=FALSE WHERE id=ANY($1::uuid[])',[members]);
    await client.query('DELETE FROM couples WHERE member_a=$1 OR member_b=$1',[req.me.id]);
    await client.query('DELETE FROM locations WHERE user_id=ANY($1::uuid[])',[members]);
    await client.query('DELETE FROM latest_locations WHERE user_id=ANY($1::uuid[])',[members]);
    await client.query('COMMIT');
    res.json({ok:true});
  }catch(e){await client.query('ROLLBACK');throw e;}finally{client.release();}
}));
app.post('/v1/privacy/sharing',safe(async(req,res)=>{
  if(typeof req.body.enabled!=='boolean')return res.status(400).json({error:'enabled true বা false দিন'});
  if(req.body.enabled && !(await getCouple(req.me.id)))return res.status(409).json({error:'প্রথমে দুজনকে যুক্ত করুন'});
  await sql('UPDATE users SET sharing_enabled=$1 WHERE id=$2',[req.body.enabled,req.me.id]);
  res.json({ok:true,sharing:req.body.enabled});
}));
app.post('/v1/location',safe(async(req,res)=>{
  if(!req.me.sharing_enabled)return res.status(403).json({error:'লোকেশন শেয়ারিং বন্ধ রয়েছে'});
  if(!(await getCouple(req.me.id)))return res.status(409).json({error:'সঙ্গী যুক্ত নেই'});
  const p=req.body;
  if(!validLocation(p))return res.status(400).json({error:'লোকেশন তথ্য ভুল, বা দুই মিনিটের বেশি পুরোনো'});
  await sql(`INSERT INTO latest_locations(user_id,latitude,longitude,accuracy_m,gps_time)
    VALUES($1,$2,$3,$4,$5) ON CONFLICT(user_id) DO UPDATE SET
    latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude,accuracy_m=EXCLUDED.accuracy_m,
    gps_time=EXCLUDED.gps_time,received_at=NOW()`,[req.me.id,p.latitude,p.longitude,p.accuracy,p.gpsTime]);
  // History sampling every 60 seconds, independent from 10–15s latest-position updates.
  await sql(`INSERT INTO locations(user_id,latitude,longitude,accuracy_m,gps_time)
    SELECT $1,$2,$3,$4,$5 WHERE NOT EXISTS (
      SELECT 1 FROM locations WHERE user_id=$1 AND received_at>NOW()-INTERVAL '60 seconds'
    )`,[req.me.id,p.latitude,p.longitude,p.accuracy,p.gpsTime]);
  // Opportunistic cleanup; periodic cleanup also runs when the API is alive.
  if(Math.random()<0.01) {
    await sql("DELETE FROM locations WHERE received_at < NOW()-INTERVAL '30 days'");
    await sql("DELETE FROM latest_locations WHERE received_at < NOW()-INTERVAL '30 days'");
  }
  res.status(201).json({ok:true});
}));
app.get('/v1/partner',safe(async(req,res)=>{
  const p=await getCouple(req.me.id);
  if(!p)return res.json({paired:false});
  const response={paired:true,name:p.name,sharing:p.sharing_enabled,location:null};
  if(p.sharing_enabled){
    const r=await sql(`SELECT latitude,longitude,accuracy_m,gps_time,received_at FROM latest_locations
      WHERE user_id=$1 AND received_at>=NOW()-INTERVAL '30 days' LIMIT 1`,[p.partner_id]);
    if(r.rows.length)response.location=r.rows[0];
  }
  res.json(response);
}));
app.get('/v1/history',safe(async(req,res)=>{
  const p=await getCouple(req.me.id);
  if(!p)return res.status(409).json({error:'কোনো সঙ্গী যুক্ত নেই'});
  if(!p.sharing_enabled)return res.json({history:[],sharing:false});
  const page=Math.max(0,Math.min(100,parseInt(req.query.page,10)||0));
  const date=String(req.query.date||'');
  if(date && !/^\d{4}-\d{2}-\d{2}$/.test(date))return res.status(400).json({error:'তারিখ ভুল'});
  const r=await sql(`SELECT latitude,longitude,accuracy_m,gps_time,received_at FROM locations
    WHERE user_id=$1 AND received_at>=NOW()-INTERVAL '30 days'
    AND ($3::text='' OR ((received_at AT TIME ZONE 'Asia/Dhaka')::date = $3::date))
    ORDER BY received_at DESC LIMIT 100 OFFSET $2`,[p.partner_id,page*100,date]);
  res.json({history:r.rows,sharing:true,page,hasMore:r.rows.length===100});
}));
app.delete('/v1/history/me',safe(async(req,res)=>{
  await sql('DELETE FROM locations WHERE user_id=$1',[req.me.id]);
  await sql('DELETE FROM latest_locations WHERE user_id=$1',[req.me.id]);
  res.json({ok:true});
}));
app.delete('/v1/account',safe(async(req,res)=>{
  const client=await pool.connect();
  try{
    await client.query('BEGIN');
    await client.query('SELECT pg_advisory_xact_lock(67221018)');
    const p=await getCouple(req.me.id,client);
    if(p){
      await client.query('UPDATE users SET sharing_enabled=FALSE WHERE id=$1',[p.partner_id]);
      await client.query('DELETE FROM locations WHERE user_id=$1',[p.partner_id]);
      await client.query('DELETE FROM latest_locations WHERE user_id=$1',[p.partner_id]);
    }
    await client.query('DELETE FROM users WHERE id=$1',[req.me.id]);
    await client.query('COMMIT');res.json({ok:true});
  }catch(e){await client.query('ROLLBACK');throw e;}finally{client.release();}
}));
app.use((err,req,res,next)=>{
  console.error(err);
  res.status(500).json({error:'সার্ভার সমস্যা; পরে চেষ্টা করুন'});
});
async function main(){
  await pool.query('SELECT 1');
  const schema=fs.readFileSync(path.join(__dirname,'schema.sql'),'utf8');
  await pool.query(schema);
  const cleanup=async()=>{
    await pool.query("DELETE FROM locations WHERE received_at < NOW()-INTERVAL '30 days'");
    await pool.query("DELETE FROM latest_locations WHERE received_at < NOW()-INTERVAL '30 days'");
  };
  await cleanup();
  setInterval(()=>cleanup().catch(console.error),6*60*60*1000).unref();
  const port=Number(process.env.PORT||3000);
  app.listen(port,'0.0.0.0',()=>console.log(`Together API listening on ${port}`));
}
main().catch(err=>{console.error('Server failed:',err);process.exit(1);});
