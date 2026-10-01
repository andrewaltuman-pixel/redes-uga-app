# Instala la APK en el emulador, la abre y falla si la app se cierra con error o no muestra la plataforma
set -x
adb install -r redes-uga.apk
# Sin la bienvenida de Chrome (en un celular real se acepta una sola vez): así se ve la plataforma
adb shell 'echo "chrome --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line'
adb shell am set-debug-app --persistent com.android.chrome
adb logcat -c
adb shell monkey -p dev.pages.redesuga.app -c android.intent.category.LAUNCHER 1
sleep 30
adb exec-out screencap -p > pantalla.png
adb logcat -d > logcat.txt
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml ui.xml
adb shell dumpsys activity activities | grep -E "topResumedActivity" || true
if grep -A 30 "FATAL EXCEPTION" logcat.txt | grep -q "dev.pages.redesuga.app"; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt
  echo "La app se cerro con error al abrir."
  exit 1
fi
if ! grep -q "Ingresar" ui.xml; then
  echo "La app abrio pero no mostro la pantalla de ingreso de la plataforma."
  exit 1
fi
echo "La app abrio y muestra la plataforma."
