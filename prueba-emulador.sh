# Instala la APK en el emulador y comprueba que abre la plataforma y que sin conexión muestra su aviso
set -x
APP=dev.pages.redesuga.app

pantalla() {
  # uiautomator a veces falla si la página todavía se está moviendo: se reintenta
  for i in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml "$1.xml" && return 0
    sleep 3
  done
  return 1
}

adb install -r redes-uga.apk
adb shell pm grant $APP android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -W -n $APP/.MainActivity
sleep 25
adb exec-out screencap -p > pantalla.png
pantalla ui
adb logcat -d > logcat.txt
if grep -A 30 "FATAL EXCEPTION" logcat.txt | grep -q "$APP"; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt
  echo "La app se cerro con error al abrir."
  exit 1
fi
if ! grep -q "Ingresar" ui.xml; then
  echo "La app abrio pero no mostro la pantalla de ingreso de la plataforma."
  exit 1
fi
echo "La app abre la plataforma."

adb shell svc wifi disable
adb shell svc data disable
adb shell am force-stop $APP
sleep 3
adb shell am start -W -n $APP/.MainActivity
sleep 15
adb exec-out screencap -p > pantalla-sin-conexion.png
pantalla ui-sin-conexion
adb shell svc wifi enable
adb shell svc data enable
if ! grep -q "Sin conexi" ui-sin-conexion.xml; then
  echo "Sin conexion la app no mostro su aviso."
  exit 1
fi
echo "Sin conexion muestra su aviso."
