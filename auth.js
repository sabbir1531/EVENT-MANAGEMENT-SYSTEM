const form = document.querySelector("form"),
  message = document.querySelector("#formMessage");
form?.addEventListener("submit", async (event) => {
  event.preventDefault();
  message.textContent = "";
  const data = Object.fromEntries(new FormData(form)),
    isLogin = form.id === "loginForm";
  try {
    const response = await fetch(
        "/api/auth/" + (isLogin ? "login" : "register"),
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(data),
        },
      );
    // Never call response.json() on an empty or non-JSON server response.
    const raw = await response.text();
    const result = raw ? JSON.parse(raw) : {};
    if (!response.ok) throw new Error(result.error);
    if (!isLogin) {
      message.textContent = result.message;
      form.reset();
      return;
    }
    localStorage.setItem("gatherlyToken", result.token);
    localStorage.setItem("gatherlyRole", result.role);
    localStorage.setItem("gatherlyName", result.name);
    // Checkout saves its destination before login. Otherwise choose the correct role dashboard.
    const returnTo = sessionStorage.getItem("gatherlyReturnTo");
    sessionStorage.removeItem("gatherlyReturnTo");
    location.href = returnTo || (result.role === "USER" ? "user-dashboard.html" : "dashboard.html");
  } catch (error) {
    message.textContent = error.message || "Please try again.";
  }
});
