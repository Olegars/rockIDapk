# rockID для Android

Клиент мессенджера rockIDserver. Подключается напрямую по TCP к `95.165.30.178:7410`: кадр из длины `uint32` (big-endian) и JSON.

Сборка:

```bat
gradlew.bat assembleRelease
```

Готовый файл: `app\build\outputs\apk\release\app-release.apk`. Подпись отладочная, приложение ставится как обычный sideload.
