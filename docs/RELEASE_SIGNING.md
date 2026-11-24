# Подпись релизов

Релизные APK форка подписываются отдельным ключом. Ключ и пароли **не хранятся в репозитории**.

## Локальная сборка

Ключ лежит вне проекта:

```
C:\Users\CheetahBaris\.keystores\byedpi-x-tg\release.jks
C:\Users\CheetahBaris\.keystores\byedpi-x-tg\credentials.txt   # alias и пароли
```

`local.properties` (gitignored) содержит:

```properties
KEYSTORE_FILE=C:/Users/CheetahBaris/.keystores/byedpi-x-tg/release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=byedpi
KEY_PASSWORD=...
```

> В `.properties` пути пишутся через `/` или с экранированными `\\`.
> `C:\Users\...` без экранирования Java Properties читает как `C:Users...`.

Сборка:

```powershell
$env:JAVA_HOME = "C:\Users\CheetahBaris\.jdks\corretto-21.0.13"
.\gradlew.bat :app:assembleRelease
```

Подписанные APK: `app/build/outputs/apk/release/`, универсальный —
`app-universal-release.apk`. Проверка подписи:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-universal-release.apk
```

## GitHub Actions

Workflow `.github/workflows/build.yml` восстанавливает `app/release.keystore` из секретов.
В настройках репозитория (**Settings → Secrets and variables → Actions**) добавьте:

| Секрет | Значение |
| --- | --- |
| `KEYSTORE_BASE64` | содержимое `release.jks.base64` (одна строка, без переносов) |
| `KEYSTORE_PASSWORD` | `KEYSTORE_PASSWORD` из `credentials.txt` |
| `KEY_ALIAS` | `byedpi` |
| `KEY_PASSWORD` | `KEY_PASSWORD` из `credentials.txt` |

Файл `release.jks.base64` уже подготовлен рядом с ключом:

```
C:\Users\CheetahBaris\.keystores\byedpi-x-tg\release.jks.base64
```

## Публикация

- Пуш в `main` → prerelease с тегом `v<versionName>-build<run number>`.
- Пуш тега `v*` → стабильный релиз с этим тегом.
- Описание релиза берётся из `.github/release-notes.md`.

```powershell
git push origin main          # prerelease
git tag v2.1.0
git push origin v2.1.0        # stable
```

## Важно

- **Сделайте резервную копию `release.jks` и паролей.** Потеря ключа = невозможность
  обновлять приложение поверх установленного (потребуется удаление и установка заново).
- Не коммитьте `*.jks`, `*.jks.base64` и `local.properties` — они уже в `.gitignore`.
  Base64-файл ключа храните вне репозитория.
- Смена ключа меняет подпись APK: обновление поверх старой сборки не установится.
