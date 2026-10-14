const net = require('node:net')
const path = require('node:path')
const release = require('../release.json')

module.exports = ({dataDir, inbox}) => {
  let stopped = false, socket, retry
  function connect() {
    if (stopped) return
    let buffer = ''
    socket = net.createConnection(path.join(dataDir,'client.sock'))
    socket.setTimeout(10000, () => socket.destroy(new Error('observation_stream_stalled')))
    socket.on('connect', () => socket.write(JSON.stringify({method:'subscribe',
      version:release.version, protocol:release.protocol})+'\n'))
    socket.on('data', chunk => {
      try {
        buffer += chunk
        if (buffer.length > 1024*1024) throw new Error('observation_frame_too_large')
        for (;;) {
          const end = buffer.indexOf('\n'); if (end < 0) break
          const frame = JSON.parse(buffer.slice(0,end)); buffer = buffer.slice(end+1)
          if (frame.compatibility?.version !== release.version || frame.compatibility?.protocol !== release.protocol)
            throw new Error('observation_version_mismatch')
          inbox.capture(frame)
        }
      } catch (e) { socket.destroy(e) }
    })
    socket.on('error', e => inbox.disconnected(e.message))
    socket.on('close', () => {
      if (!stopped) {inbox.disconnected('observation_stream_disconnected'); retry = setTimeout(connect,1000)}
    })
  }
  connect()
  return {stop:() => {stopped=true; clearTimeout(retry); socket?.destroy()}}
}
