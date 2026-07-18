# Gatherly Event Management System

A raw Java + Oracle SQL event-ticketing platform. The browser UI is plain HTML/CSS/JavaScript; the server uses only the JDK HTTP server and JDBC (no React, Express, Spring, or ORM).

## Roles

- **User:** browse event details, log in, create an order, then continue to payment.
- **Event Manager:** create, update, and delete events using the manager workspace.
- **Admin:** has the same event management rights.

Public registration always creates a `USER`. Promote the first trusted account to `ADMIN` in Oracle using the statement in `backend/schema.sql`; an Admin can then be used as the initial privileged operator.

## Local setup

1. Create an Oracle user/schema, then run [backend/schema.sql](backend/schema.sql) in SQL*Plus once.
2. Install a JDK (17+) and download the Oracle JDBC driver (`ojdbc11.jar`) matching the Oracle server.
3. Copy `backend/config.properties.example` to `backend/config.properties` and set the database plus SSLCommerz sandbox values. Do not commit this file.
4. From `backend`, compile and run:

```powershell
javac -d out src\com\gatherly\App.java
java -cp "out;path\to\ojdbc11.jar" com.gatherly.App
```

5. Open `http://localhost:8080`.

## Payments and maps

`POST /api/payments/sslcommerz` securely builds the information required to hand off an authenticated order to SSLCommerz. Connect it to the SSLCommerz server-side initiate-payment call with the configured store credentials, and implement validated success/fail/IPN callbacks before production. Never expose `ssl.store_password` in the browser.

The event detail page includes a Google Maps embed. Event records store latitude and longitude so the API can produce an event-specific map link/embed when the detail screen is bound to API data.

## API overview

- `POST /api/auth/register`, `POST /api/auth/login`
- `POST /api/admin/staff` — Admin only; creates `ADMIN` or `EVENT_MANAGER`
- `GET /api/events`, `GET /api/events/{id}`
- `POST /api/events`, `PUT /api/events/{id}`, `DELETE /api/events/{id}` — Admin/Event Manager only
- `POST /api/orders` — authenticated users only
- `POST /api/payments/sslcommerz` — authenticated order owner only
