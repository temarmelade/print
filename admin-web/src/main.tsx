import React from "react";
import ReactDOM from "react-dom/client";
// Базовые стили — первыми, чтобы стили страниц могли их уточнять.
import "./index.css";
import App from "./App.tsx";
import { installStackTables } from "./lib/stackTables.ts";

installStackTables();

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
