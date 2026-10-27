'use strict';
const crypto = require('node:crypto');
function normalizedPhone(value) {
  const p = String(value || '').trim().replace(/[\s-]/g,'');
  if (/^01[3-9][0-9]{8}$/.test(p)) return '+88' + p;
  if (/^\+8801[3-9][0-9]{8}$/.test(p)) return p;
  return null;
}
function inviteCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let result = '';
  for (let i=0;i<10;i++) result += alphabet[crypto.randomInt(alphabet.length)];
  return result;
}
function codeHash(secret, code) {
  return crypto.createHmac('sha256', secret).update(String(code).trim().toUpperCase()).digest('hex');
}
function validLocation(o) {
  return o && Number.isFinite(o.latitude) && Math.abs(o.latitude) <= 90
    && Number.isFinite(o.longitude) && Math.abs(o.longitude) <= 180
    && Number.isFinite(o.accuracy) && o.accuracy >= 0 && o.accuracy <= 50000
    && typeof o.gpsTime === 'string'
    && Number.isFinite(Date.parse(o.gpsTime))
    && Math.abs(Date.now() - Date.parse(o.gpsTime)) < 120000;
}
module.exports={normalizedPhone,inviteCode,codeHash,validLocation};
