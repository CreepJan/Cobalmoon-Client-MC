import { mount } from "svelte";
import "./lib/tokens.css";
import "./app.css";
import App from "./App.svelte";

// In `npm run dev` opened in a plain browser there is no Tauri backend: use sample data.
if (import.meta.env.DEV && !("__TAURI_INTERNALS__" in window)) {
  await import("./lib/devMock");
}

export default mount(App, { target: document.getElementById("app")! });
