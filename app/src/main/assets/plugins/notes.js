/* @plugin
{
  "id": "builtin.notes",
  "name": "Заметки",
  "version": "1.0",
  "author": "Expressive Keyboard",
  "description": "Мод: сохраняйте выделенный или скопированный текст и вставляйте его одним касанием. Заметки хранятся только на телефоне.",
  "icon": "@note",
  "permissions": ["read_text", "clipboard"]
}
*/

var notes = kb.storage.get("notes", []);

function save() {
  kb.storage.set("notes", notes);
  kb.refresh();
}

function addNote(text) {
  text = (text || "").trim();
  if (!text) {
    kb.status("Нечего сохранить: выделите текст или скопируйте его");
    return;
  }
  notes = notes.filter(function (n) { return n !== text; });
  notes.unshift(text);
  if (notes.length > 50) notes.length = 50;
  save();
  kb.status("Сохранено в заметки");
}

kb.addPanel({
  id: "notes",
  title: "Заметки",
  icon: "@note",
  render: function () {
    var ui = [
      { type: "row", children: [
        { type: "button", text: "Из выделения", icon: "@select_all", onClick: function () { addNote(kb.selectedText()); } },
        { type: "button", text: "Из буфера", icon: "@clipboard", onClick: function () { addNote(kb.clipboard()[0]); } }
      ] }
    ];
    if (notes.length === 0) {
      ui.push({ type: "text", text: "Пока пусто. Выделите текст в поле и нажмите «Из выделения».", center: true });
    }
    notes.forEach(function (n, i) {
      ui.push({ type: "row", children: [
        { type: "button", text: n.length > 38 ? n.slice(0, 38) + "…" : n, onClick: function () { kb.commit(n); } },
        { type: "button", icon: "@delete", onClick: function () { notes.splice(i, 1); save(); } }
      ] });
    });
    return ui;
  }
});
