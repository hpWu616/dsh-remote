import dgram from 'node:dgram';
import { networkInterfaces, hostname } from 'node:os';

const DISCOVERY_PORT = 19389;
const MDNS_PORT = 5353;
const MDNS_GROUP = '224.0.0.251';

function privateAddress(address) {
  const parts = address.split('.').map(Number);
  return parts.length === 4 && (parts[0] === 10
    || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
    || (parts[0] === 192 && parts[1] === 168));
}

export function lanInterfaces() {
  return Object.values(networkInterfaces()).flat().filter(item =>
    item.family === 'IPv4' && !item.internal && privateAddress(item.address));
}

export function lanNetworks(interfaces = lanInterfaces()) {
  return interfaces.map(item => {
    const address = octets(item.address);
    const mask = octets(item.netmask);
    const network = address.map((part, index) => part & mask[index]).join('.');
    const prefix = mask.reduce((count, part) => count +
      part.toString(2).split('1').length - 1, 0);
    return { address: item.address, subnet: `${network}/${prefix}` };
  });
}

function octets(address) {
  return address.split('.').map(Number);
}

function broadcastAddress(address, mask) {
  const ip = octets(address);
  const bits = octets(mask);
  return ip.map((value, index) => value | (255 ^ bits[index])).join('.');
}

function sameSubnet(a, b, mask) {
  const left = octets(a);
  const right = octets(b);
  const bits = octets(mask);
  return left.every((value, index) => (value & bits[index]) === (right[index] & bits[index]));
}

function dnsName(name) {
  return Buffer.concat([...name.split('.').map(part => Buffer.concat([
    Buffer.from([Buffer.byteLength(part)]), Buffer.from(part)])), Buffer.from([0])]);
}

function queryName(packet, offset, depth = 0) {
  if (depth > 6 || offset >= packet.length) return null;
  const parts = [];
  let position = offset;
  let next;
  for (;;) {
    if (position >= packet.length) return null;
    const length = packet[position++];
    if (length === 0) { next ??= position; break; }
    if ((length & 0xc0) === 0xc0) {
      if (position >= packet.length) return null;
      const pointer = ((length & 0x3f) << 8) | packet[position++];
      next ??= position;
      const target = queryName(packet, pointer, depth + 1);
      if (!target) return null;
      parts.push(target.name);
      break;
    }
    if (length > 63 || position + length > packet.length) return null;
    parts.push(packet.subarray(position, position + length).toString('ascii'));
    position += length;
  }
  return { name: parts.join('.').toLowerCase(), next };
}

export function isHostQuery(packet, host) {
  if (packet.length < 12 || packet.readUInt16BE(2) & 0x8000) return false;
  let offset = 12;
  const count = Math.min(packet.readUInt16BE(4), 20);
  for (let i = 0; i < count; i++) {
    const question = queryName(packet, offset);
    if (!question || question.next + 4 > packet.length) return false;
    offset = question.next;
    const type = packet.readUInt16BE(offset);
    offset += 4;
    if (question.name === host && (type === 1 || type === 255)) return true;
  }
  return false;
}

export function hostAnswer(host, address) {
  const name = dnsName(host);
  const record = Buffer.alloc(10);
  record.writeUInt16BE(1, 0);
  record.writeUInt16BE(0x8001, 2);
  record.writeUInt32BE(30, 4);
  record.writeUInt16BE(4, 8);
  const header = Buffer.alloc(12);
  header.writeUInt16BE(0x8400, 2);
  header.writeUInt16BE(1, 6);
  return Buffer.concat([header, name, record, Buffer.from(octets(address))]);
}

export function startDiscovery(pairing, port) {
  const host = `dsh-${pairing.hostId}.local`;
  const discovery = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  const mdns = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  let beaconTimer;
  const close = () => {
    clearInterval(beaconTimer);
    try { discovery.close(); } catch { /* already closed */ }
    try { mdns.close(); } catch { /* already closed */ }
  };
  discovery.on('error', error => console.error('dsh Remote discovery:', error.message));
  mdns.on('error', error => console.error('dsh Remote mDNS:', error.message));
  discovery.on('message', (packet, remote) => {
    if (packet.length > 512) return;
    let request;
    try { request = JSON.parse(packet.toString('utf8')); } catch { return; }
    if (request.type !== 'dsh-discover' || !/^[a-f0-9]{16,64}$/.test(request.nonce)) return;
    if (!lanInterfaces().some(item => sameSubnet(remote.address, item.address, item.netmask))) return;
    const response = Buffer.from(JSON.stringify({ type: 'dsh-host',
      nonce: request.nonce, id: pairing.hostId, name: hostname(), host, port }));
    discovery.send(response, remote.port, remote.address);
  });
  discovery.bind(DISCOVERY_PORT, () => {
    discovery.setBroadcast(true);
    const beacon = () => {
      const payload = Buffer.from(JSON.stringify({ type: 'dsh-host',
        id: pairing.hostId, name: hostname(), host, port }));
      for (const item of lanInterfaces()) {
        discovery.send(payload, DISCOVERY_PORT,
          broadcastAddress(item.address, item.netmask));
      }
    };
    beacon();
    beaconTimer = setInterval(beacon, 3000);
  });
  mdns.on('message', (packet, remote) => {
    if (!isHostQuery(packet, host)) return;
    const adapter = lanInterfaces().find(item =>
      sameSubnet(remote.address, item.address, item.netmask));
    if (!adapter) return;
    const response = hostAnswer(host, adapter.address);
    mdns.send(response, MDNS_PORT, MDNS_GROUP);
    mdns.send(response, remote.port, remote.address);
  });
  mdns.bind(MDNS_PORT, () => {
    for (const item of lanInterfaces()) {
      try { mdns.addMembership(MDNS_GROUP, item.address); }
      catch (error) { console.error('dsh Remote mDNS membership:', error.message); }
    }
  });
  return close;
}
