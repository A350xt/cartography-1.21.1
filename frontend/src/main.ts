import "./style.css";

import { bootstrapApp } from "./app";

const root = document.querySelector<HTMLElement>("#app");

if (!root) {
  throw new Error("Cartography root element not found");
}

void bootstrapApp(root);
