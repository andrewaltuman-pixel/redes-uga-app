# Instala la APK en el emulador, la abre y falla si la app se cierra con error
set -x
adb install -r redes-uga.apk
adb logcat -c
adb shell monkey -p dev.pages.redesuga.app -c android.intent.category.LAUNCHER 1
sleep 25
adb exec-out screencap -p > pantalla.png
adb logcat -d > logcat.txt
adb shell dumpsys activity activities | grep -E "topResumedActivity" || true
if grep -A 30 "FATAL EXCEPTION" logcat.txt | grep -q "dev.pages.redesuga.app"; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt
  echo "La app se cerro con error al abrir."
  exit 1
fi
echo "La app abrio sin errores."
