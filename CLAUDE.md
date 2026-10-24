## Как выпускать новую версию
Когда я прошу «выпусти новую версию»:
1. Проверь, что ветка main и всё совпадает с origin/main (git pull).
2. В app/build.gradle.kts увеличь versionCode на 1 и поставь versionName на следующую версию (последнюю цифру +1, если я не сказал другое).
3. Собери release APK и назови его ExpressiveKeyboard-<версия>-release.apk.
4. Закоммить все изменения с понятным сообщением на английском и запушь в main.
5. Создай релиз v<версия> через gh (путь: "/c/Program Files/GitHub CLI/gh.exe", если gh не находится), приложи APK, описание изменений на английском, коротким списком.
6. В конце покажи мне ссылку на релиз.

Правила:
- Репозиторий: kotlalipopus/Expressive-Keyboard, аккаунт GitHub — kotlalipopus.
- Никогда не коммить ключи подписи, пароли, local.properties, папки build/, dist/, .kotlin/.
- Не переписывай историю на GitHub (без force push).
