// Require a logged-in account before the checkout page can be opened.
document.querySelector("#checkoutButton")?.addEventListener("click", event => {
  event.preventDefault();
  const eventId = new URLSearchParams(location.search).get("id");
  if (eventId) sessionStorage.setItem("gatherlyEventId", eventId);
  if (!localStorage.getItem("gatherlyToken")) {
    sessionStorage.setItem("gatherlyReturnTo", "cart.html");
    location.href = "login.html";
    return;
  }
  location.href = "cart.html";
});
