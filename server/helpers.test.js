const test=require('node:test');
const assert=require('node:assert/strict');
const {normalizedPhone,inviteCode,codeHash,validLocation}=require('./helpers');
test('BD phone normalization',()=>{
  assert.equal(normalizedPhone('01712345678'),'+8801712345678');
  assert.equal(normalizedPhone('+8801712345678'),'+8801712345678');
  assert.equal(normalizedPhone('01212345678'),null);
});
test('invite codes have good format and hashed',()=>{
  const c=inviteCode();
  assert.match(c,/^[A-HJ-NP-Z2-9]{10}$/);
  assert.equal(codeHash('k',c),codeHash('k',c.toLowerCase()));
});
test('GPS validation rejects invalid and old positions',()=>{
 const o={latitude:23.81,longitude:90.41,accuracy:15,gpsTime:new Date().toISOString()};
 assert.ok(validLocation(o));
 assert.equal(validLocation({...o,latitude:200}),false);
 assert.equal(validLocation({...o,gpsTime:'2020-01-01T00:00:00Z'}),false);
});
