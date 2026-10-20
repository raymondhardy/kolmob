#!/usr/bin/env bash
# Log the debug app in using the (git-ignored) ./auth file: key=value lines: user=... and pass=....
# Handles spaces/quotes in either value; never prints the credentials.
set -u
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
cd "$(dirname "$0")/.."
user=$(grep -i -m1 '^user=' auth | cut -d= -f2- | tr -d '\r\n'); pass=$(grep -i -m1 '^pass=' auth | cut -d= -f2- | tr -d '\r\n')
[ -n "$user" ] && [ -n "$pass" ] || { echo "auth file needs user=... and pass=... lines"; exit 2; }
"$A" logcat -c
q=$(printf %q "$user:$pass")
"$A" shell "am broadcast -a com.example.kolmafiaandroid.RUN --es login $q" >/dev/null
for i in $(seq 1 90); do
  r=$("$A" logcat -d -s KolEngine | grep "LOGIN RESULT" | tail -1)
  [ -n "$r" ] && { echo "$r" | sed 's/.*LOGIN RESULT/LOGIN RESULT/'; exit 0; }
  sleep 1
done
echo "no login result after 90s"; exit 1
