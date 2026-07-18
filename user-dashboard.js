const token=localStorage.getItem("gatherlyToken");
if(!token) location.href="login.html";
document.querySelector("#userName").textContent=localStorage.getItem("gatherlyName")||"My account";
const readJson=async response=>{const raw=await response.text();return raw?JSON.parse(raw):{};};

async function loadUserPage(){
  try{
    const eventsResponse=await fetch("/api/events"); const events=await readJson(eventsResponse);
    document.querySelector("#availableEvents").innerHTML=events.map(event=>`<article class="user-event"><p class="event-date">${event.date}</p><h2>${event.title}</h2><p>${event.venue}</p><p>From BDT ${event.price}</p><a class="button button-dark" href="event.html?id=${event.id}">View & book</a></article>`).join("")||"<p>No events are available yet. Please check again later.</p>";
    const ordersResponse=await fetch("/api/orders",{headers:{Authorization:`Bearer ${token}`}});const orders=await readJson(ordersResponse);
    if(!ordersResponse.ok) throw new Error(orders.error||"Could not load tickets.");
    document.querySelector("#orderList").innerHTML=orders.map(order=>`<article><b>${order.title}</b><small>${order.date} · ${order.quantity} ticket(s) · BDT ${order.amount}</small><span>${order.status}</span></article>`).join("")||"<p>You have not bought a ticket yet.</p>";
  }catch(error){document.querySelector("#availableEvents").textContent=error.message;}
}
document.querySelector("#logout").onclick=()=>{localStorage.clear();location.href="index.html";};loadUserPage();
