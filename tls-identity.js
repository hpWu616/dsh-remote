import { createHash, generateKeyPairSync, randomBytes, sign, X509Certificate } from 'node:crypto';

const RECORD = 'dsh-remote-host/tls-identity';
const signatureAlgorithm = Buffer.from('300a06082a8648ce3d040302', 'hex');

function der(tag, data) {
  const size = data.length;
  let length;
  if (size < 128) length = Buffer.from([size]);
  else if (size < 256) length = Buffer.from([0x81, size]);
  else length = Buffer.from([0x82, size >> 8, size & 255]);
  return Buffer.concat([Buffer.from([tag]), length, data]);
}

function sequence(...items) { return der(0x30, Buffer.concat(items)); }
function integer(bytes) { return der(0x02, bytes); }
function utc(date) {
  return der(0x17, Buffer.from(date.toISOString().slice(2, 19)
    .replace(/[-:T]/g, '') + 'Z'));
}
function pem(label, bytes) {
  const lines = bytes.toString('base64').match(/.{1,64}/g).join('\n');
  return `-----BEGIN ${label}-----\n${lines}\n-----END ${label}-----\n`;
}

export function createIdentity(hostId) {
  if (!/^[a-f0-9]{12}$/.test(hostId)) throw new Error('Invalid host identity');
  const { privateKey, publicKey } = generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
  });
  const key = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const commonName = sequence(der(0x31, sequence(
    der(0x06, Buffer.from('550403', 'hex')),
    der(0x0c, Buffer.from(`dsh-${hostId}.local`)),
  )));
  const now = new Date();
  const from = new Date(now.getTime() - 86_400_000);
  const until = new Date(now);
  until.setUTCFullYear(until.getUTCFullYear() + 10);
  const serial = randomBytes(16);
  serial[0] &= 0x7f;
  const body = sequence(
    der(0xa0, integer(Buffer.from([2]))), integer(serial),
    signatureAlgorithm, commonName, sequence(utc(from), utc(until)), commonName,
    publicKey.export({ type: 'spki', format: 'der' }),
  );
  const signature = sign('sha256', body, privateKey);
  const certificate = pem('CERTIFICATE', sequence(body, signatureAlgorithm,
    der(0x03, Buffer.concat([Buffer.from([0]), signature]))));
  const fingerprint = createHash('sha256')
    .update(new X509Certificate(certificate).raw).digest('hex');
  return { key, certificate, fingerprint };
}

export async function loadTlsIdentity(credentials, hostId) {
  await credentials.modifyRecord(RECORD, current => current ?? {
    kind: 'grant', payload: createIdentity(hostId),
  });
  const record = await credentials.readRecord(RECORD);
  const identity = record?.payload;
  if (record?.kind !== 'grant' || !identity?.key || !identity?.certificate
      || !/^[a-f0-9]{64}$/.test(identity?.fingerprint)) {
    throw new Error('Invalid dsh Remote TLS identity');
  }
  const actual = createHash('sha256')
    .update(new X509Certificate(identity.certificate).raw).digest('hex');
  if (actual !== identity.fingerprint) throw new Error('TLS identity changed');
  return identity;
}
