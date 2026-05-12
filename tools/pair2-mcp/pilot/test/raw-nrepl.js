// Direct test of bencode against the nREPL on port 17777.
const bencode = require('bencode');
const net = require('node:net');

const sock = net.createConnection({host:'127.0.0.1', port:17777});
let buf = Buffer.alloc(0);
sock.on('connect', () => {
  console.log('connected; sending eval');
  const msg = bencode.encode({op:'eval', code:'(+ 1 2)', id:'test-1'});
  console.log('sending', msg.length, 'bytes:', msg.toString('utf8').slice(0,100));
  sock.write(msg);
});
sock.on('data', (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  console.log('---chunk; total buf size =', buf.length);
  console.log('raw bytes:', JSON.stringify(buf.toString('utf8').slice(0, 400)));
  try {
    const decoded = bencode.decode(buf, 'utf8');
    console.log('decoded:', JSON.stringify(decoded));
  } catch (e) {
    console.log('decode threw:', e.message);
  }
});
sock.on('error', (e) => console.log('err:', e.message));
setTimeout(() => { sock.end(); process.exit(0); }, 3000);
