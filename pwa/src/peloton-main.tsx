import React from "react";
import ReactDOM from "react-dom/client";
import PelotonApp from "./peloton/PelotonApp";
import "./peloton/peloton.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <PelotonApp />
  </React.StrictMode>,
);

if ("serviceWorker" in navigator && !import.meta.env.DEV) {
  window.addEventListener("load", () => {
    void navigator.serviceWorker
      .register(`${import.meta.env.BASE_URL}peloton/sw.js`, {
        scope: `${import.meta.env.BASE_URL}peloton/`,
      })
      .catch(() => {});
  });
}
