/* @plugin
{
  "id": "my.first.mod",
  "name": "Мой первый мод",
  "version": "1.0",
  "author": "Я",
  "description": "Пример мода: своя панель, реакция на слова, свои подсказки и хранилище",
  "icon": "🚀",
  "permissions": ["read_text"],
  "layouts": {
    "symbols2": [
      ["~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"],
      ["£", "€", "$", "¥", "^", "°", "=", "{", "}", "\\"],
      ["{page}", "%", "©", "®", "™", { "label": "λ", "action": "lambda" }, "[", "]", "{backspace}"]
    ]
  }
}
*/

// Манифест выше — обычный JSON плагина: в нём можно использовать всё из PLUGINS.md
// (actions, shortcuts, symbols, theme, skin, settings, layouts, icons, languages…).
// Ниже — скрипт. Он выполняется в песочнице: есть объект kb, console, setTimeout/setInterval.

// Счётчик набранных слов хранится между запусками
var stats = kb.storage.get("stats", { words: 0 });

// 1. Событие после каждого набранного слова (нужно разрешение read_text)
kb.on("word", function (e) {
  stats.words++;
  if (stats.words % 10 === 0) kb.storage.set("stats", stats);
});

// 2. Свои подсказки: пока набираете «спс», по бокам строки подсказок появятся варианты
kb.on("suggest", function (e) {
  if (e.word.toLowerCase() === "спс") return ["спасибо большое", "благодарю"];
});

// 3. Замена при вводе: две точки подряд превращаются в многоточие
kb.on("key", function (e) {
  if (e.text === "." && kb.textBefore(1) === ".") {
    kb.deleteBefore(1);
    return "…";            // вернуть строку — ввести её вместо буквы; вернуть false — отменить нажатие
  }
});

// 4. Своя панель: render() возвращает список элементов, kb.refresh() перерисовывает
kb.addPanel({
  id: "main",
  title: "Мой мод",
  icon: "🚀",
  render: function () {
    return [
      { type: "text", text: "Слов набрано: " + stats.words, size: 18, bold: true },
      { type: "row", children: [
        { type: "button", text: "Дата", icon: "@date", onClick: function () { kb.commit(new Date().toLocaleDateString()); } },
        { type: "button", text: "Сбросить", icon: "@refresh", onClick: function () {
          stats.words = 0;
          kb.storage.set("stats", stats);
          kb.refresh();
        } }
      ] },
      { type: "grid", items: ["😀", "🔥", "👍", "❤️", "🎉", "🤔"], onClick: function (item) { kb.commit(item); } }
    ];
  }
});

// 5. Команда для своей клавиши: в раскладке выше клавиша λ вызывает её
kb.addCommand("lambda", function () { kb.commit("λ"); });

console.log("Мой мод загружен");
