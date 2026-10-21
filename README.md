# rockID для Android

Клиент мессенджера rockIDserver. Подключается напрямую по TCP к `192.168.222.2:7410`: кадр из длины `uint32` (big-endian) и JSON.

Сборка:

```bat
gradlew.bat assembleRelease
```

Готовый файл: `app\build\outputs\apk\release\app-release.apk`. Подпись отладочная, приложение ставится как обычный sideload.
