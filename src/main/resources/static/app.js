// Filtering, totals and saving happen in Java (see ItemController);
// this file only shows the screens and talks to /api.

const money = new Intl.NumberFormat(undefined, {style: "currency", currency: "EUR"});
// Phone photos are often several MB; they are made at most this many pixels wide and high before
// uploading, so they upload quickly over mobile data and take less space on the NAS
const MAX_PICTURE_PIXELS = 1600;
const SUPPORTED_PICTURES = ["image/jpeg", "image/png", "image/gif", "image/webp"];

const TYPE_NAMES = {SOLD: "Sold", BOUGHT: "Bought", FOR_SALE: "For sale"};

let filter = {type: "", category: "", place: "", q: "", sort: "newest"};
let options = {categories: [], places: []}; // what was entered before, offered in the editor's dropdowns
let editing = null; // the item open in the editor, or null when adding a new one
let adding = false; // the editor was opened with "Add item" (stays true if saving needs a second try)
let pendingPicture = null; // picture chosen in the editor, uploaded on save
let pendingPhoto = null; // how pendingPicture was rotated and cropped, so editing it again starts from the original
let removePicture = false;

// ---------- helpers ----------

const $ = (id) => document.getElementById(id);

function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    Object.assign(node, props);
    node.append(...children);
    return node;
}

async function api(method, url, body) {
    const isForm = body instanceof FormData;
    const response = await fetch(url, {
        method,
        headers: body && !isForm ? {"Content-Type": "application/json"} : {},
        body: isForm ? body : body ? JSON.stringify(body) : undefined,
    });
    if (response.status === 401) {
        // this device hasn't entered the access code yet
        location.href = "/login.html";
        throw new Error("Please enter the access code.");
    }
    const data = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
        throw new Error(data?.error || "Something went wrong.");
    }
    return data;
}

// "2026-10" -> "Oct 2026"
function formatMonth(yearMonth) {
    const [year, month] = yearMonth.split("-").map(Number);
    return new Date(year, month - 1).toLocaleDateString(undefined, {month: "short", year: "numeric"});
}

// Prices are typed into a text field, because number fields on some phones (Safari with a German keyboard)
// throw away "1,50". Accepts 1,50 / 1.50 / 1 / € 1,50; returns null for anything else.
function parsePrice(text) {
    const cleaned = text.replace(/[€\s]/g, "").replace(",", ".");
    return /^\d+(\.\d{1,2})?$/.test(cleaned) ? Number(cleaned) : null;
}

// 1.5 -> "1,50" or "1.50", depending on the device's language
function priceText(price) {
    return Number(price).toLocaleString(undefined, {
        minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false,
    });
}

function plural(count, word) {
    return `${count} ${word}${count === 1 ? "" : "s"}`;
}

function setThumb(node, imageUrl) {
    node.style.backgroundImage = imageUrl ? `url("${imageUrl}")` : "";
    node.textContent = imageUrl ? "" : "🎀";
}

// A smaller JPEG copy of the picture, or the picture itself when that isn't smaller (or the browser
// can't read it; then the server says whether the type is OK)
async function shrinkPicture(file) {
    if (file.type === "image/gif") return file; // would lose the animation
    try {
        const bitmap = await createImageBitmap(file);
        const scale = Math.min(1, MAX_PICTURE_PIXELS / Math.max(bitmap.width, bitmap.height));
        const canvas = el("canvas", {
            width: Math.round(bitmap.width * scale),
            height: Math.round(bitmap.height * scale),
        });
        const context = canvas.getContext("2d");
        context.fillStyle = "#ffffff"; // JPEG has no transparency
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
        const jpeg = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.85));
        if (jpeg && (jpeg.size < file.size || !SUPPORTED_PICTURES.includes(file.type))) return jpeg;
    } catch (error) {
        // e.g. a HEIC photo on a browser that can't show it
    }
    return file;
}

// ---------- list ----------

async function loadItems() {
    const params = new URLSearchParams();
    if (filter.type) params.set("type", filter.type);
    if (filter.category) params.set("category", filter.category);
    if (filter.place) params.set("place", filter.place);
    if (filter.q) params.set("q", filter.q);
    if (filter.sort !== "newest") params.set("sort", filter.sort);
    const {items, totals} = await api("GET", "/api/items?" + params);
    if (filter.type === "FOR_SALE" && totals.forSaleCount === 0) {
        // the last item for sale was sold or deleted, and its card disappears: show all items instead
        filter.type = "";
        return loadItems();
    }
    showTotals(totals);
    for (const card of $("type-filter").children) {
        card.setAttribute("aria-pressed", String(filter.type !== "" && card.dataset.type === filter.type));
    }
    showItems(items);
}

function showTotals(totals) {
    $("sold-sum").textContent = money.format(totals.sold);
    $("sold-count").textContent = plural(totals.soldCount, "item");
    $("bought-sum").textContent = money.format(totals.bought);
    $("bought-count").textContent = plural(totals.boughtCount, "item");
    $("balance").textContent = money.format(totals.balance);
    $("for-sale-card").hidden = totals.forSaleCount === 0;
    $("for-sale-sum").textContent = money.format(totals.forSale);
    $("for-sale-count").textContent = plural(totals.forSaleCount, "item") + " · asking";

    const parts = [];
    if (filter.category) parts.push(`in “${filter.category}”`);
    if (filter.place) parts.push(`at “${filter.place}”`);
    if (filter.q) parts.push(`matching “${filter.q}”`);
    $("scope").hidden = parts.length === 0;
    $("scope").textContent = "Totals for items " + parts.join(" ");
}

function showItems(items) {
    const list = $("list");
    list.replaceChildren(...items.map(itemRow));

    const filtered = filter.type || filter.category || filter.place || filter.q;
    $("empty").hidden = items.length > 0;
    $("empty").textContent = filtered
        ? "No items match your filters."
        : "No items yet. Click “Add item” to log your first sale or purchase.";
}

function itemRow(item) {
    const thumb = el("div", {className: "thumb"});
    setThumb(thumb, item.imageUrl);
    if (item.imageUrl) {
        thumb.title = "Show picture";
        thumb.style.cursor = "zoom-in";
        thumb.addEventListener("click", (event) => {
            event.stopPropagation();
            $("viewer-image").src = item.imageUrl;
            $("viewer").showModal();
        });
    }

    const meta = [item.category, item.place, item.date && formatMonth(item.date)].filter(Boolean).join(" · ");
    const row = el("li", {className: "item " + item.type},
        thumb,
        el("div", {className: "info"},
            el("div", {className: "name", textContent: item.name}),
            el("div", {className: "meta", textContent: meta})),
        el("div", {className: "price"},
            el("span", {className: "badge " + item.type, textContent: TYPE_NAMES[item.type]}),
            money.format(item.price)));
    if (item.type === "FOR_SALE") {
        // opens the item already marked as sold, so the price it went for can be changed before saving
        const sold = el("button", {type: "button", className: "sold-button", textContent: "✓ Sold"});
        sold.addEventListener("click", (event) => {
            event.stopPropagation();
            openEditor(item);
            markSold();
        });
        row.querySelector(".price").append(sold);
    }
    row.addEventListener("click", () => openEditor(item));
    return row;
}

// Fills the category and place filters; the editor's dropdowns are filled when it opens
async function loadOptions() {
    options = await api("GET", "/api/options");
    fillFilter("category", options.categories, "All categories");
    fillFilter("place", options.places, "All places");
}

// key = "category" or "place": the filter value and the dropdown #<key>-filter
function fillFilter(key, values, allLabel) {
    // the spelling in the list ("" when its last item was changed or deleted)
    filter[key] = values.find((v) => v.toLowerCase() === filter[key].toLowerCase()) ?? "";
    $(key + "-filter").replaceChildren(
        el("option", {value: "", textContent: allLabel}),
        ...values.map((v) => el("option", {value: v, textContent: v})),
        el("option", {value: EDIT_CATEGORIES, textContent: "✎ Edit categories & places…"}));
    $(key + "-filter").value = filter[key];
}

async function refresh() {
    try {
        await loadOptions();
        await loadItems();
    } catch (error) {
        $("empty").hidden = false;
        $("empty").textContent = error.message;
    }
}

// ---------- editor ----------

const NEW_CHOICE = "__new__";

// A dropdown with the values entered before, "none" and "＋ New…". Picking "＋ New…" shows the text field
// next to it, which is what gets saved; picking anything else copies that into the text field.
function fillChoice(select, input, values, current, newLabel) {
    select.replaceChildren(
        el("option", {value: "", textContent: "— none —"}),
        ...values.map((v) => el("option", {value: v, textContent: v})),
        el("option", {value: NEW_CHOICE, textContent: newLabel}));
    select.value = current ?? "";
    input.value = current ?? "";
    input.hidden = true;
}

function choiceChanged(select, input) {
    const isNew = select.value === NEW_CHOICE;
    input.hidden = !isNew;
    input.value = isNew ? "" : select.value;
    if (isNew) input.focus();
}

function openEditor(item) {
    editing = item;
    adding = !item;
    pendingPicture = null;
    pendingPhoto = null;
    removePicture = false;

    const form = $("item-form");
    form.reset();
    $("editor-title").textContent = item ? "Edit item" : "Add item";
    $("delete").hidden = !item;
    $("form-error").hidden = true;

    form.elements.type.value = item?.type ?? (filter.type || "SOLD");
    form.elements.name.value = item?.name ?? "";
    form.elements.price.value = item ? priceText(item.price) : "";
    form.elements.date.value = item?.date ?? ""; // stays empty unless a month is picked
    fillChoice($("place-choice"), form.elements.place, options.places,
        item ? item.place : (filter.place || null), "＋ New place…");
    fillChoice($("category-choice"), form.elements.category, options.categories,
        item ? item.category : (filter.category || null), "＋ New category…");
    form.elements.notes.value = item?.notes ?? "";
    updateTypeLabels();
    showPreview(item?.imageUrl);

    $("editor").showModal();
    // start at the top: browsers put the cursor in the first field (on the iPhone that opens the keyboard and
    // scrolls down to it), and the window would keep where it was scrolled to last time
    document.activeElement?.blur();
    $("editor").scrollTop = 0;
}

function updateTypeLabels() {
    const type = $("item-form").elements.type.value;
    $("place-label").textContent = {SOLD: "Where sold", BOUGHT: "Where bought", FOR_SALE: "Where listed"}[type];
    $("price-label").textContent = type === "FOR_SALE" ? "Asking price (€)" : "Price (€)";
    $("mark-sold").hidden = !(editing && type === "FOR_SALE");
}

// An item for sale was sold: it becomes sold this month; the price can still be changed before saving
function markSold() {
    const fields = $("item-form").elements;
    const now = new Date();
    fields.type.value = "SOLD";
    fields.date.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
    updateTypeLabels();
    fields.price.focus();
    fields.price.select();
}

function showPreview(url) {
    setThumb($("preview"), url);
    $("remove-picture").hidden = !url;
    $("edit-picture").hidden = !url;
}

function showFormError(message) {
    $("form-error").textContent = message;
    $("form-error").hidden = false;
}

async function save(event) {
    event.preventDefault();
    const fields = $("item-form").elements;
    const price = parsePrice(fields.price.value);
    if (price === null) {
        showFormError("Please enter a price like 1,50 or 12.");
        return;
    }
    const body = {
        type: fields.type.value,
        name: fields.name.value,
        price,
        date: fields.date.value || null,
        place: fields.place.value,
        category: fields.category.value,
        notes: fields.notes.value,
    };
    try {
        const item = editing
            ? await api("PUT", `/api/items/${editing.id}`, body)
            : await api("POST", "/api/items", body);
        editing = item; // if the picture upload fails, saving again updates this item instead of adding another

        if (pendingPicture) {
            const picture = await shrinkPicture(pendingPicture);
            const data = new FormData();
            data.append("file", picture, picture === pendingPicture ? pendingPicture.name : "picture.jpg");
            await api("POST", `/api/items/${item.id}/image`, data);
        } else if (removePicture && item.imageUrl) {
            await api("DELETE", `/api/items/${item.id}/image`);
        }
        $("editor").close();
        await refresh();
        if (adding) window.scrollTo({top: 0, behavior: "smooth"}); // back to the totals, which now include it
    } catch (error) {
        showFormError(error.message);
    }
}

async function deleteItem() {
    if (!confirm(`Delete “${editing.name}”?`)) return;
    try {
        await api("DELETE", `/api/items/${editing.id}`);
        $("editor").close();
        await refresh();
    } catch (error) {
        showFormError(error.message);
    }
}

// ---------- picture editor ----------

const MAX_EDIT_PIXELS = 3000; // the photo is edited at this size at most, the result is MAX_PICTURE_PIXELS at most
const MIN_CROP = 0.1; // the crop frame is at least a tenth of the picture wide and high
const WHOLE_PHOTO = {x: 0, y: 0, w: 1, h: 1};

// The photo being edited: image = canvas with the photo as chosen, rotation = 0, 90, 180 or 270 degrees
// clockwise, crop = the part kept, as fractions (0..1) of the rotated photo, turned = the rotated photo
let photo = null;
let photoDone = null; // resolves the promise of editPhoto
let drag = null;

async function loadPhoto(blob) {
    const bitmap = await createImageBitmap(blob);
    const scale = Math.min(1, MAX_EDIT_PIXELS / Math.max(bitmap.width, bitmap.height));
    const canvas = el("canvas", {
        width: Math.round(bitmap.width * scale),
        height: Math.round(bitmap.height * scale),
    });
    canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close?.();
    return canvas;
}

// Shows the picture editor; resolves to {file, photo} with "Use picture", null with "Cancel"
function editPhoto(start) {
    photo = {...start, crop: {...start.crop}};
    $("photo").showModal();
    drawPhoto();
    return new Promise((resolve) => photoDone = resolve);
}

function turn(image, rotation) {
    const sideways = rotation % 180 !== 0;
    const canvas = el("canvas", {
        width: sideways ? image.height : image.width,
        height: sideways ? image.width : image.height,
    });
    const context = canvas.getContext("2d");
    context.translate(canvas.width / 2, canvas.height / 2);
    context.rotate(rotation * Math.PI / 180);
    context.drawImage(image, -image.width / 2, -image.height / 2);
    return canvas;
}

// Fits the rotated photo into the dialog
function drawPhoto() {
    photo.turned = turn(photo.image, photo.rotation);
    const {width, height} = photo.turned;
    const scale = Math.min($("photo-stage").clientWidth / width, window.innerHeight * 0.55 / height);
    const canvas = $("photo-canvas");
    canvas.width = Math.round(width * scale * devicePixelRatio);
    canvas.height = Math.round(height * scale * devicePixelRatio);
    canvas.style.width = $("photo-frame").style.width = Math.round(width * scale) + "px";
    canvas.style.height = $("photo-frame").style.height = Math.round(height * scale) + "px";
    canvas.getContext("2d").drawImage(photo.turned, 0, 0, canvas.width, canvas.height);
    showCrop();
}

function showCrop() {
    const {x, y, w, h} = photo.crop;
    Object.assign($("crop").style, {left: x * 100 + "%", top: y * 100 + "%", width: w * 100 + "%", height: h * 100 + "%"});
}

const clamp = (value, min, max) => Math.min(Math.max(value, min), max);

// mode = move, or the corner being dragged: nw, ne, sw or se
function moveCrop(mode, dx, dy, start) {
    let {x, y, w, h} = start;
    if (mode === "move") {
        x = clamp(x + dx, 0, 1 - w);
        y = clamp(y + dy, 0, 1 - h);
    }
    if (mode.includes("w")) {
        const left = clamp(x + dx, 0, x + w - MIN_CROP);
        w = x + w - left;
        x = left;
    }
    if (mode.includes("e")) w = clamp(w + dx, MIN_CROP, 1 - x);
    if (mode.includes("n")) {
        const top = clamp(y + dy, 0, y + h - MIN_CROP);
        h = y + h - top;
        y = top;
    }
    if (mode.includes("s")) h = clamp(h + dy, MIN_CROP, 1 - y);
    return {x, y, w, h};
}

// The cropped photo as a JPEG of at most MAX_PICTURE_PIXELS
async function photoFile() {
    const {turned, crop} = photo;
    const sx = crop.x * turned.width, sy = crop.y * turned.height;
    const sw = crop.w * turned.width, sh = crop.h * turned.height;
    const scale = Math.min(1, MAX_PICTURE_PIXELS / Math.max(sw, sh));
    const canvas = el("canvas", {width: Math.round(sw * scale), height: Math.round(sh * scale)});
    const context = canvas.getContext("2d");
    context.fillStyle = "#ffffff"; // JPEG has no transparency
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(turned, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);
    const jpeg = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.85));
    return new File([jpeg], "picture.jpg", {type: "image/jpeg"});
}

$("crop").addEventListener("pointerdown", (event) => {
    event.preventDefault();
    drag = {mode: event.target.dataset.mode ?? "move", x: event.clientX, y: event.clientY, crop: photo.crop};
    $("crop").setPointerCapture(event.pointerId);
});

$("crop").addEventListener("pointermove", (event) => {
    if (!drag) return;
    const frame = $("photo-frame").getBoundingClientRect();
    photo.crop = moveCrop(drag.mode, (event.clientX - drag.x) / frame.width, (event.clientY - drag.y) / frame.height,
        drag.crop);
    showCrop();
});

for (const type of ["pointerup", "pointercancel"]) {
    $("crop").addEventListener(type, () => drag = null);
}

// Clockwise; the crop frame turns along
$("photo-rotate").addEventListener("click", () => {
    const {x, y, w, h} = photo.crop;
    photo.rotation = (photo.rotation + 90) % 360;
    photo.crop = {x: 1 - y - h, y: x, w: h, h: w};
    drawPhoto();
});

$("photo-reset").addEventListener("click", () => {
    photo.rotation = 0;
    photo.crop = WHOLE_PHOTO;
    drawPhoto();
});

$("photo-cancel").addEventListener("click", () => $("photo").close());

$("photo-done").addEventListener("click", async () => {
    const done = photoDone;
    photoDone = null;
    const {image, rotation, crop} = photo;
    done({file: await photoFile(), photo: {image, rotation, crop}});
    $("photo").close();
});

// Cancel, or Escape
$("photo").addEventListener("close", () => {
    photoDone?.(null);
    photoDone = null;
});

// ---------- categories and places ----------

const EDIT_CATEGORIES = "__edit__";

// what = "category" or "place"; url = where they are read, renamed and deleted
const CHOICE_LISTS = [
    {what: "category", url: "/api/categories", list: "category-list"},
    {what: "place", url: "/api/places", list: "place-list"},
];

async function openCategories() {
    $("categories-error").hidden = true;
    await showCategories();
    $("categories").showModal();
}

async function showCategories() {
    for (const kind of CHOICE_LISTS) {
        const choices = await api("GET", kind.url);
        $(kind.list).replaceChildren(...choices.map((choice) => choiceRow(kind, choice)));
        $(kind.list + "-empty").hidden = choices.length > 0;
    }
}

function choiceRow(kind, choice) {
    const input = el("input", {value: choice.name, autocomplete: "off", ariaLabel: "Name"});
    const rename = el("button", {type: "button", textContent: "Rename", hidden: true});
    const remove = el("button", {type: "button", className: "danger", textContent: "Delete"});
    input.addEventListener("input", () => {
        rename.hidden = input.value.trim() === "" || input.value.trim() === choice.name;
    });
    input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            event.preventDefault(); // would close the dialog
            if (!rename.hidden) rename.click();
        }
    });
    rename.addEventListener("click", () => changeChoice(kind, choice, input.value.trim()));
    remove.addEventListener("click", () => {
        if (confirm(`Delete the ${kind.what} “${choice.name}”? Its ${plural(choice.count, "item")} stay, without a ${kind.what}.`)) {
            changeChoice(kind, choice, null);
        }
    });
    return el("li", {},
        el("div", {className: "category-name"}, input,
            el("span", {className: "muted", textContent: plural(choice.count, "item")})),
        rename, remove);
}

// to = null deletes the category or place
async function changeChoice(kind, choice, to) {
    try {
        if (to === null) {
            await api("DELETE", kind.url + "?" + new URLSearchParams({name: choice.name}));
        } else {
            await api("PUT", kind.url, {from: choice.name, to});
        }
        if (filter[kind.what].toLowerCase() === choice.name.toLowerCase()) {
            filter[kind.what] = to ?? ""; // keep showing the renamed category or place
        }
        $("categories-error").hidden = true;
        await showCategories();
        await refresh();
    } catch (error) {
        $("categories-error").textContent = error.message;
        $("categories-error").hidden = false;
    }
}

// ---------- events ----------

$("add").addEventListener("click", () => openEditor(null));

$("type-filter").addEventListener("click", (event) => {
    const card = event.target.closest("button");
    if (!card) return;
    filter.type = card.dataset.type === filter.type ? "" : card.dataset.type;
    loadItems().catch((error) => alert(error.message));
});

for (const key of ["category", "place"]) {
    $(key + "-filter").addEventListener("change", (event) => {
        if (event.target.value === EDIT_CATEGORIES) {
            event.target.value = filter[key];
            openCategories().catch((error) => alert(error.message));
            return;
        }
        filter[key] = event.target.value;
        loadItems().catch((error) => alert(error.message));
    });
}

$("sort").addEventListener("change", (event) => {
    filter.sort = event.target.value;
    loadItems().catch((error) => alert(error.message));
});

let searchTimer;
$("search").addEventListener("input", (event) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
        filter.q = event.target.value.trim();
        loadItems().catch((error) => alert(error.message));
    }, 200);
});

$("item-form").addEventListener("submit", save);
$("item-form").addEventListener("change", (event) => {
    if (event.target.name === "type") updateTypeLabels();
});
$("cancel").addEventListener("click", () => $("editor").close());
$("place-choice").addEventListener("change", () => choiceChanged($("place-choice"), $("item-form").elements.place));
$("category-choice").addEventListener("change",
    () => choiceChanged($("category-choice"), $("item-form").elements.category));
$("delete").addEventListener("click", deleteItem);
$("mark-sold").addEventListener("click", markSold);

$("picture").addEventListener("change", async (event) => {
    const file = event.target.files[0];
    event.target.value = ""; // so choosing the same picture again works
    if (!file) return;
    let image;
    try {
        image = await loadPhoto(file);
    } catch (error) {
        // e.g. a HEIC photo on a browser that can't show it: used as it is, the server says whether it's OK
        pendingPicture = file;
        pendingPhoto = null;
        removePicture = false;
        showPreview(URL.createObjectURL(file));
        return;
    }
    const edited = await editPhoto({image, rotation: 0, crop: WHOLE_PHOTO});
    if (edited) usePhoto(edited);
});

$("edit-picture").addEventListener("click", async () => {
    let photo = pendingPicture && pendingPhoto;
    if (!photo) {
        try {
            const picture = pendingPicture ?? await (await fetch(editing.imageUrl)).blob();
            photo = {image: await loadPhoto(picture), rotation: 0, crop: WHOLE_PHOTO};
        } catch (error) {
            showFormError("This picture can't be edited here.");
            return;
        }
    }
    const edited = await editPhoto(photo);
    if (edited) usePhoto(edited);
});

function usePhoto({file, photo}) {
    pendingPicture = file;
    pendingPhoto = photo;
    removePicture = false;
    showPreview(URL.createObjectURL(file));
}

$("remove-picture").addEventListener("click", () => {
    pendingPicture = null;
    removePicture = true;
    $("picture").value = "";
    showPreview(null);
});

$("viewer").addEventListener("click", () => $("viewer").close());

refresh();
