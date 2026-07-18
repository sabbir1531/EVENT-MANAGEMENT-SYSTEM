// This page is available only after a privileged login.
const token = localStorage.getItem("gatherlyToken");
const role = localStorage.getItem("gatherlyRole");
if (!token || !["ADMIN", "EVENT_MANAGER"].includes(role)) location.href = "login.html";

document.querySelector("#userRole").textContent = `${localStorage.getItem("gatherlyName")} - ${role}`;
if (role === "ADMIN") document.querySelector("#adminTools").hidden = false;

const api = (url, options = {}) => fetch(url, {
  ...options,
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}`, ...options.headers }
});
let eventsById = {};

async function loadEvents() {
  const response = await api("/api/manage/events");
  const events = await response.json();
  eventsById = Object.fromEntries(events.map(event => [event.id, event]));
  document.querySelector("#eventList").innerHTML = events.map(event => `
    <article><b>${event.title}</b><small>${event.venue} | BDT ${event.price}</small>
    <span><button data-edit="${event.id}">Edit</button><button data-delete="${event.id}">Delete</button></span></article>`
  ).join("") || "<p>No events yet.</p>";
}

document.querySelector("#eventForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.target;
  const data = Object.fromEntries(new FormData(form));
  const id = data.id;
  delete data.id;
  const response = await api(`/api/events${id ? `/${id}` : ""}`, {
    method: id ? "PUT" : "POST", body: JSON.stringify(data)
  });
  const result = await response.json();
  if (!response.ok) return alert(result.error || "Could not save the event.");
  form.reset();
  await loadEvents();
});

document.querySelector("#eventList").addEventListener("click", async (event) => {
  const id = event.target.dataset.edit || event.target.dataset.delete;
  if (!id) return;
  if (event.target.dataset.edit) {
    const item = eventsById[id];
    const form = document.querySelector("#eventForm");
    form.id.value = item.id;
    form.title.value = item.title;
    form.description.value = item.description;
    form.eventDate.value = item.date.replace(" ", "T").slice(0, 16);
    form.venue.value = item.venue; form.address.value = item.address;
    form.latitude.value = item.lat; form.longitude.value = item.lng;
    form.price.value = item.price; form.imageUrl.value = item.imageUrl || "";
    form.scrollIntoView({ behavior: "smooth" });
    return;
  }
  if (confirm("Delete this event?")) {
    const response = await api(`/api/events/${id}`, { method: "DELETE" });
    if (!response.ok) return alert("Could not delete the event.");
    await loadEvents();
  }
});

// The server verifies ADMIN again; hiding this form alone is not security.
document.querySelector("#staffForm")?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const response = await api("/api/admin/staff", {
    method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(event.target)))
  });
  const raw = await response.text();
  const result = raw ? JSON.parse(raw) : {};
  if (!response.ok) return alert(result.error || "Could not create the account.");
  alert(result.message);
  event.target.reset();
});

document.querySelector("#logout").onclick = () => { localStorage.clear(); location.href = "index.html"; };
loadEvents();
