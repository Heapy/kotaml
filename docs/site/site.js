const status = document.getElementById("copy-status");

function highlightCode(element) {
  if (!window.hljs) return;
  delete element.dataset.highlighted;
  window.hljs.highlightElement(element);
}

const installTabList = document.querySelector(".install-tabs");
const installTabs = [...installTabList.querySelectorAll('[role="tab"]')];
function selectInstallTab(selected, focus = false) {
  for (const tab of installTabs) {
    const active = tab === selected;
    tab.setAttribute("aria-selected", String(active));
    tab.tabIndex = active ? 0 : -1;
    document.getElementById(tab.getAttribute("aria-controls")).hidden = !active;
  }
  document.getElementById("catalog-option").hidden = selected.id === "tab-maven";
  if (focus) selected.focus();
}
for (const [index, tab] of installTabs.entries()) {
  const panel = document.getElementById(tab.getAttribute("aria-controls"));
  panel.setAttribute("role", "tabpanel");
  panel.setAttribute("aria-labelledby", tab.id);
  panel.tabIndex = 0;
  tab.addEventListener("click", () => selectInstallTab(tab));
  tab.addEventListener("keydown", (event) => {
    let next;
    if (event.key === "ArrowRight") next = (index + 1) % installTabs.length;
    else if (event.key === "ArrowLeft") next = (index - 1 + installTabs.length) % installTabs.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = installTabs.length - 1;
    else return;
    event.preventDefault();
    selectInstallTab(installTabs[next], true);
  });
}
selectInstallTab(installTabs[0]);
installTabList.hidden = false;

const useCatalog = document.getElementById("use-catalog");
const includeJson = document.getElementById("include-json");
const dependencySnippets = [...document.querySelectorAll("[data-json-line], [data-json-template]")].map(element => ({ element, base: element.textContent }));
function updateInstallOptions() {
  document.getElementById("ktc-direct").hidden = useCatalog.checked;
  document.getElementById("ktc-catalog").hidden = !useCatalog.checked;
  document.getElementById("gradle-direct").hidden = useCatalog.checked;
  document.getElementById("gradle-catalog").hidden = !useCatalog.checked;
  for (const { element, base } of dependencySnippets) {
    let code = base;
    if (includeJson.checked) {
      const extra = element.dataset.jsonTemplate
        ? `\n\n${document.getElementById(element.dataset.jsonTemplate).content.textContent}`
        : `\n${element.dataset.jsonLine}`;
      const index = element.dataset.jsonPosition === "end" ? base.length
        : element.dataset.jsonPosition === "before-settings" ? base.indexOf("\n\nsettings:")
        : base.lastIndexOf("\n}");
      code = base.slice(0, index) + extra + base.slice(index);
    }
    element.textContent = code;
    highlightCode(element);
  }
}
useCatalog.addEventListener("change", updateInstallOptions);
includeJson.addEventListener("change", updateInstallOptions);
updateInstallOptions();
document.querySelector(".install-options").hidden = false;
for (const code of document.querySelectorAll("pre code:not([data-highlighted])")) highlightCode(code);

if (navigator.clipboard && window.isSecureContext) {
  for (const button of document.querySelectorAll("[data-copy]")) {
    button.hidden = false;
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(document.getElementById(button.dataset.copy).textContent);
        button.textContent = "Copied";
        status.textContent = "Code copied to clipboard.";
        setTimeout(() => { button.textContent = "Copy"; }, 1800);
      } catch {
        status.textContent = "Could not copy automatically. Select the code and copy it manually.";
      }
    });
  }
}

const links = [...document.querySelectorAll('.sidebar a[href^="#"]')];
if ("IntersectionObserver" in window) {
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      for (const link of links) {
        if (link.hash === `#${entry.target.id}`) link.setAttribute("aria-current", "location");
        else link.removeAttribute("aria-current");
      }
    }
  }, { rootMargin: "-10% 0px -65% 0px" });
  for (const link of links) observer.observe(document.querySelector(link.hash));
}
