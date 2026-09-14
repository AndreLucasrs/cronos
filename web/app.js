(function () {
  "use strict";

  var projectsEl = document.getElementById("projects");
  var chatLog = document.getElementById("chat-log");

  function api(path, options) {
    return fetch(path, Object.assign({ headers: { "Content-Type": "application/json" } }, options))
      .then(function (res) {
        return res.json().then(function (body) {
          if (!res.ok) throw body;
          return body;
        });
      });
  }

  function renderProjects(projects) {
    projectsEl.innerHTML = "";
    projects.forEach(function (project) {
      var card = document.createElement("div");
      card.className = "project-card";
      card.innerHTML =
        "<h3>" + escapeHtml(project.name) + "</h3>" +
        "<form class=\"task-form\" data-project=\"" + project.id + "\">" +
        "<input type=\"text\" placeholder=\"Nova tarefa\" required>" +
        "<input type=\"date\">" +
        "<button type=\"submit\">Adicionar</button>" +
        "</form>" +
        "<ul class=\"task-list\" id=\"tasks-" + project.id + "\"></ul>";
      projectsEl.appendChild(card);

      card.querySelector("form").addEventListener("submit", function (ev) {
        ev.preventDefault();
        var inputs = ev.target.querySelectorAll("input");
        var title = inputs[0].value.trim();
        var dueDate = inputs[1].value || null;
        if (!title) return;
        api("/api/tasks", {
          method: "POST",
          body: JSON.stringify({ projectId: project.id, title: title, dueDate: dueDate })
        }).then(function () {
          inputs[0].value = "";
          inputs[1].value = "";
          loadTasks(project.id);
        });
      });

      loadTasks(project.id);
    });
  }

  function loadTasks(projectId) {
    api("/api/projects/" + projectId + "/tasks").then(function (tasks) {
      var list = document.getElementById("tasks-" + projectId);
      list.innerHTML = "";
      tasks.forEach(function (task) {
        var li = document.createElement("li");
        li.className = "task-row";
        li.innerHTML =
          "<span class=\"title\">" + escapeHtml(task.title) +
          (task.dueDate ? " <span class=\"due\">(" + task.dueDate + ")</span>" : "") +
          "</span>" +
          "<select>" +
          ["todo", "doing", "done"].map(function (s) {
            return "<option value=\"" + s + "\"" + (s === task.status ? " selected" : "") + ">" + s + "</option>";
          }).join("") +
          "</select>";
        li.querySelector("select").addEventListener("change", function (ev) {
          api("/api/tasks/" + task.id + "/status", {
            method: "PATCH",
            body: JSON.stringify({ status: ev.target.value })
          });
        });
        list.appendChild(li);
      });
    });
  }

  function escapeHtml(text) {
    var div = document.createElement("div");
    div.textContent = text;
    return div.innerHTML;
  }

  function loadProjects() {
    api("/api/projects").then(renderProjects);
  }

  document.getElementById("project-form").addEventListener("submit", function (ev) {
    ev.preventDefault();
    var input = document.getElementById("project-name");
    var name = input.value.trim();
    if (!name) return;
    api("/api/projects", { method: "POST", body: JSON.stringify({ name: name }) }).then(function () {
      input.value = "";
      loadProjects();
    });
  });

  document.getElementById("chat-form").addEventListener("submit", function (ev) {
    ev.preventDefault();
    var input = document.getElementById("chat-input");
    var message = input.value.trim();
    if (!message) return;
    appendChat("user", message);
    input.value = "";
    api("/api/assistant/chat", { method: "POST", body: JSON.stringify({ message: message }) })
      .then(function (res) { appendChat("assistant", res.reply); })
      .catch(function (err) { appendChat("error", err.error === "blocked" ? "Bloqueado pelo guard: " + err.reasonCode : "Erro: " + (err.error || "desconhecido")); });
  });

  function appendChat(role, text) {
    var div = document.createElement("div");
    div.className = "chat-msg " + role;
    div.textContent = text;
    chatLog.appendChild(div);
    chatLog.scrollTop = chatLog.scrollHeight;
  }

  loadProjects();
})();
