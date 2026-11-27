
#!/bin/bash
set -e

if [ -z "$VNC_PASSWORD" ]; then
    echo "ERROR: Set VNC_PASSWORD in Render environment variables."
    exit 1
fi

export DISPLAY=:99

# Start virtual display
Xvfb :99 -screen 0 1024x700x16 &
sleep 2

# Start desktop window manager
openbox &

# Configure VNC password
x11vnc -storepasswd "$VNC_PASSWORD" /tmp/vnc.pass

# Start protected VNC server
x11vnc -display :99 \
    -rfbauth /tmp/vnc.pass \
    -forever \
    -shared \
    -rfbport 5900 &

# Start browser-accessible noVNC
websockify --web=/usr/share/novnc/ \
    0.0.0.0:${PORT:-10000} \
    127.0.0.1:5900 &

# Launch the actual Java Swing application
exec java -jar /app/app.jar
