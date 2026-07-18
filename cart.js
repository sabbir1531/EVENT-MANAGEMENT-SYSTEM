// Cart access requires login. Return here after the user logs in.
const checkoutToken = localStorage.getItem("gatherlyToken");
if (!checkoutToken) {
  sessionStorage.setItem("gatherlyReturnTo", "cart.html");
  location.href = "login.html";
}

document.querySelector("#checkoutForm")?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const eventId = sessionStorage.getItem("gatherlyEventId");
  if (!eventId) {
    alert("Please open an event and choose checkout before buying a ticket.");
    location.href = "index.html";
    return;
  }
  try {
    const orderResponse = await fetch("/api/orders", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${checkoutToken}` },
      body: JSON.stringify({ eventId, quantity: 1 })
    });
    const orderRaw = await orderResponse.text();
    const order = orderRaw ? JSON.parse(orderRaw) : {};
    if (!orderResponse.ok) throw new Error(order.error || "Could not create the order.");

    // The next page must post the returned order ID to /api/payments/sslcommerz.
    sessionStorage.setItem("gatherlyOrderId", order.orderId);
    alert("Order created. Payment integration is the next step.");
    location.href = "cart.html";
  } catch (error) {
    alert(error.message);
  }
});
