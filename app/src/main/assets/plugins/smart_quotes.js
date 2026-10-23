/* @plugin
{
  "id": "builtin.smart-quotes",
  "name": "Ёлочки «»",
  "version": "1.0",
  "author": "Expressive Keyboard",
  "description": "Мод: кавычка \" сама становится « или » — в зависимости от того, открываете вы цитату или закрываете. Пример события key.",
  "icon": "«»",
  "permissions": ["read_text"],
  "enabledByDefault": false
}
*/

kb.on("key", function (e) {
  if (e.text !== "\"") return;
  var before = kb.textBefore(1);
  var opening = before === "" || /[\s(\[{«„—-]$/.test(before);
  return opening ? "«" : "»";
});
