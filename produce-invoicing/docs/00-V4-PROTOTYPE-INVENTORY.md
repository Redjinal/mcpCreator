# V4 prototype inventory (Task 00 audit)

> Produced 2026-11-13 by reading `reference/Produce Invoicing Prototype.zip` (v4 + Invoice Document + support.js) as **data only** — nothing was executed. Spot-checked against source: `autoNumber`/`padStart(5)`, `cents()` formula, `isWide`, `#2D5E3E` accent, absence of `localStorage`/`fetch`. The prototype is a UX reference, not Android source; its numbering (`INV-00129`, one shared sequence), simulated print/sync and presenter controls are explicitly **not** ported (see `docs/13-TRACEABILITY-MATRIX.md`).


Sources: `reference/…/Produce Invoicing v4.dc.html` (template lines 9–1545, logic class `Component extends DCLogic` lines 1546–2539), `reference/…/Invoice Document.dc.html`, `reference/…/support.js`.
I read these files and did not run them. All identifiers below are quoted from the code.

**support.js** is a generic "dc-runtime" (header comment: `GENERATED from dc-runtime/src/*.ts`). It has these modules: react, parse, boot, expr, encode, compile, logic, component, bundled, cdn, external, atomics, helmet, pseudo, registry, runtime and stream-state. It compiles `<x-dc>` templates (`sc-if`, `sc-for`, `dc-import`, `{{ }}` bindings) into React. It loads React 18.3.1, ReactDOM 18.3.1 and Babel standalone 7.29.0 from unpkg, with SRI hashes. It contains no invoice or produce logic (a grep for "invoice" or "produce" returns 0 hits).

Component props (from `data-props`):
- `defaultView`: landscape | portrait | phone | desktop | fit
- `startRole`: d1 | d2 | owner
- `showPresenterBar`: boolean

---

## 1. Screens / views

Roles are `role` = `'d1'` (Driver 1), `'d2'` (Driver 2) or `'owner'` (`roleLabel()`). Drivers see only invoices where `x.driver === role` (`visible`). The owner sees all of them.

| Identifier | Role(s) | Purpose |
|---|---|---|
| `screen:'create'`, `step:'edit'` (`showEditor`) | all | Draft editor in three steps: "1 · Customer", "2 · Invoice date", "3 · Items". Also holds the invoice-number block and the total and issue footer. |
| `screen:'create'`, `step:'review'` (`showReview`, narrow only) | all | Full-screen "Review invoice" that embeds `Invoice Document` with `draftDoc`. On wide layouts this is replaced by the right-hand "Live preview" pane (`previewW`). |
| `screen:'invoices'` (`onInvoices`) | all | Invoice list. Wide layouts show a table (`showTable`, `tableCols`). Narrow layouts show cards (`showCards`). Has filters, search, date range and a result summary. |
| `showDaily` block inside invoices | owner only | "Daily review by driver" (labelled Proposed). Per-day table for Driver 1, Driver 2 and Both drivers. Can be collapsed with `showDailyCollapsed`. |
| `rangeOpen` popover (`role=dialog` "Choose date range") | all | Range presets (`RANGES`: today, 7d, 1m, 3m, 6m, 1y) plus a custom From/To range. |
| `screen:'detail'` (`onDetail`, needs `viewInvId`) | owner, or the driver who owns the invoice | Issued invoice record: top bar, prev/next through the filtered list, status fields, the QuickBooks manual mark, meta, simulated print history and an embedded document. |
| `screen:'print'` (`onPrint`) | same as detail | "Print preview": paper toggle (`paper` = `'roll'` gives 302px, `'full'` gives 680px), print status and a Print/Reprint button. |
| `screen:'customers'` (`onCustomers`) | all; add is owner only | Customer list with search. Each row has "New invoice" and "Details" buttons. |
| `screen:'customer'` (`onCustomer`). Viewing: `cdViewing`. Editing: `cdEditing`, `form.kind:'customer'` | view: all; edit/add: owner | Customer detail with invoice history, or the add/edit form. |
| `screen:'products'` (`onProducts`), `prodFilter` = active/all | all; add is owner only | Product catalog list showing units and stored prices. |
| `screen:'product'` (`onProduct`). Viewing: `pdViewing`. Editing: `pdEditing`, `form.kind:'product'` | view: all; edit/deactivate/reactivate/delete: owner | Product detail, or the form (name, permitted units, optional price per unit). |
| `screen:'settings'` (`onSettings`) | view: all; edit: owner (`f.editable`) | Business details, numbering (prefix and next number), currency (BBD), units, Connections (Printer: Simulated; QuickBooks: Later phase), Reset sample data. |
| `sheet:'customer'` (`sheetCustomer`), dialog "Choose customer" | all | Searchable picker with keyboard support (↑ ↓ Enter Esc, `hi` index) and "Recent" shortcuts (Proposed). |
| `sheet:'product'` (`sheetProduct`), dialog "Add products" | all | Searchable catalog. Each product has per-unit add buttons. Shows "Last ordered by …" recent items and "Add “x” as custom line" when nothing matches. |
| `modal.type:'issue'` (`modalIssue`) | all | "Issue invoice {number}?" confirmation with a summary of Customer, Date, Items and Total. |
| `modal.type:'print'` (`modalPrint`). `modal.phase` is `select`, `sending`, `done`, `unavailable` or `uncertain` | detail/print viewers | Simulated printer picker and the result screens. |
| `modal.type:'sync'` (`modalSync`), dialog "Save status" | all | Sync status explanation, counts (Waiting to save, Not saved, Needs review, Last checked) and a retry action. |
| `modal.type:'offlineIssue'` (`modalOfflineIssue`) | all | "Issuing waits for a connection" alert dialog. |
| `modal.type:'confirm'` (`modalConfirm`) | all | Generic confirm dialog: `title`, `body`, `label`, `cancel`, `danger`, `fn`. |
| `toast` (`hasToast`) | all | Bottom toast with an optional action, for example Undo. |
| `notes` drawer (`notesOpen`), `notesTab` = `main`, `offline` or `future` | presenter | Right-side drawer, 460px wide: unresolved decisions, calculation checks, demo assumptions, offline issuance comparison, future QuickBooks phase. |
| Presenter bar (`showPresenterBar`) | presenter | Controls for Demo role, View, Data, Future phase, Offline option, Presenter notes, Scenario and Next print result. |
| Shell | all | Wide layouts (`isWide`) have a left sidebar with the "+ Create invoice" CTA, a sync button, `navSecondary` with counts, and a "Using as (demo)" role box. Narrow layouts have a top bar and a 5-tab bottom nav (`navAll`: Create, Invoices, Customers, Products, Settings). |

Empty and loading states:
- First use: `invFirstUse`, `custFirstUse`, `prodFirstUse`, `noCustomersAtAll`, `noActiveProducts`.
- No results: `invFilteredEmpty`, `custNoResults`, `prodNoResults`, `noCustomerResults`, `noProductResults`.
- Loading: `isLoading`, a skeleton plus Retry.
- Partial load failure: `showLoadPartial`.

## 2. Interactions per screen

**Create / editor**
- **Choose customer / Change** → `sheet:'customer'`. Picking a customer runs `updateDraft({customerId})` and closes the sheet. A Recent chip calls `pickCustomer`.
- **Date**
  - The Today and Yesterday chips (`dateChips`) set `draft.date` (ISO) and `dateText` (DD/MM/YYYY).
  - Typing in the field (`onDateText`) runs `parseDate()`. An invalid value sets `date:''`.
  - **Calendar** (`toggleCal`) opens `cal{open,y,m}`. It has month and year selects (2020–2032) and ‹ › buttons (`shiftCal`). Picking a day sets the date and closes the calendar.
- **+ Add product** opens `sheet:'product'`. The per-unit button runs `addProduct(p, unit)`:
  - If a line already exists with the same product and unit, its qty goes up by 1.
  - Otherwise a new line is appended with `price = stored.toFixed(2)` or `''`.
- **Add custom line** (`addCustomLine`) appends `{productId:null, desc:'', qty:'1', unit:'each', price:'', priceTouched:true}`.
- **Line edits**
  - `onDesc`, `onQty` and `onPrice` call `setLine`. Commas become dots. Editing the price sets `priceTouched:true` and `zeroOk:false`.
  - The − and + buttons call `stepQty`.
  - Unit is a segmented control when there are 4 or fewer units, otherwise a select. It calls `setUnit`, which re-applies the stored price unless `priceTouched` and always sets the `unitNote` text "Quantity is not converted between units".
- **Remove** (`removeLine`) removes the line and shows a toast with Undo (7s). Undo re-inserts the line at its original index.
- **Zero price**: when the price is 0, a "Confirm no charge for this line" checkbox toggles `zeroOk`.
- **Override number (owner)** (`openOverride`) sets `overrideOpen:true`. The `onOverride` input edits `numberOverride`. **Use automatic** clears both.
- **Discard draft** opens a confirm, then removes the draft from `invoices` and sets `draft:null`. **New invoice** (`startNew`) sets `draft:null` and shows the toast "Previous draft kept in Invoices".
- **Review invoice** (narrow layouts) sets `step:'review'` and turns `showErrors` on if there are problems. **Back to edit** sets `step:'edit'`.
- **Issue invoice** (`tryIssue`):
  - If there are `problems`, it sets `showErrors:true`, which shows the banner and field errors.
  - If offline, it opens `modal offlineIssue`.
  - Otherwise it opens `modal issue`.
- **Confirm Issue** (`confirmIssue`):
  - The `this.issuing` guard plus the `draftId` lookup make it idempotent.
  - The number is either the manual override or `autoNumber()`. The auto path sets `settings.nextSeq = n+1`.
  - Builds an issued record (see §3), replaces the draft in `invoices`, and sets `screen:'print'`, `viewInvId`, `draft:null`.
  - Shows the toast "Invoice issued and saved · not printed yet".
- **Conflict banner**: "Keep this tablet’s version" or "Use other device’s version" calls `resolveConflict(useOther)`, which sets `sync:'synced'` and clears `conflict`.
- **Offline banner**: "Check connection" only shows a toast. **Resumed banner**: Dismiss sets `resumed:null`.
- **Autosave**: every `updateDraft` runs `withDraft`. Once the draft has a customer or lines, it is upserted into `invoices` as `status:'draft'` with `updatedAt`. The draft's `sync` becomes `'pending'` if offline, otherwise `'synced'`. `flashSaving()` sets `saving:true` for 650ms.

**Invoices list**
- Status filter (`statusFilter`): all, issued or draft. The owner also gets `unmarked` (issued and not `qbManual`).
- Driver filter (`driverFilter`, owner only).
- `range` (preset or `custom` with `rangeFrom`/`rangeTo`). `applyCustom` checks that both dates are present and from ≤ to.
- `listSearch` matches number, id and customer.
- Filter chips can be removed one by one. **Clear filters** (`clearInvFilters`) resets them all.
- Sort order: drafts first, then date descending, then number descending.
- Opening a row: an issued invoice goes to `screen:'detail'`. A draft is opened with `openDraft`, which copies it into `draft` and goes to `screen:'create'`.
- Daily review:
  - ‹ › and the date input change `reviewDate`. "Go to latest day with invoices" sets `reviewDate:null`.
  - "Show in list" sets a custom single-day range plus `driverFilter`.
  - Hide and Show toggle `showDaily`.

**Detail**
- Back returns to `invoices` or to the customer.
- Prev/next (`goRel`) move through the filtered issued rows.
- **Print preview** sets `screen:'print'`. **Print invoice / Reprint** calls `openPrint(id)`, which opens `modal{type:'print', phase:'select', printer:lastPrinter}`.
- Uncertain banner: "It printed correctly" calls `resolveUncertain(id, true)`. "Nothing printed · print again" calls `openPrint`.
- The owner's QuickBooks checkbox calls `toggleQb(id)`, which sets `qbManual = {at: ISO, by:'Owner'}` or `null` and shows a toast with Undo (6s).

**Print modal**
- Pick a printer (`pA` or `pB`), then Print calls `sendPrint()`:
  - Phase goes to `sending`, then after a 1200ms timeout to `done`, `unavailable` or `uncertain`.
  - Appends `{at, printer, result}` to `printLog`.
  - On success: `prints+1`, `printStatus:'printed'`.
  - On uncertain: `printStatus:'uncertain'`.
  - Otherwise: `printStatus` is `'printed'` if prints > 0, else `'failed'`.
- On the result screens: Retry, Choose another printer, "Print later · view record" (`printToRecord`). On an uncertain result: Yes, printed / No, print again / Check later.

**Customers / Products / Settings**
- Customers:
  - The form is validated by `custErrs`: name required and unique (case-insensitive); phone regex `^[+\d][\d\s\-()]{5,}$`.
  - `saveCustomerForm` upserts and sorts by name, sets `saved.msg`, and increments `pending` if offline.
  - "New invoice" from a customer (`startFor`) creates a draft with that `customerId`.
- Products:
  - `prodErrs`: name required and unique; at least one unit; each price is optional, valid, and not 0.
  - `saveProductForm` stores `price` as `parseFloat` or `null`.
  - Deactivate (confirm) sets `active:false`. Reactivate sets it back.
  - **Delete** is only possible when the product is unused (`usage(id)===0`). It removes the product and returns to `products`.
- Settings:
  - `setS` creates a `form{kind:'settings'}`.
  - `sErrs`: name non-empty; prefix ≤8 characters; nextSeq matching `^\d{1,7}$` and >0.
  - `saveSettings` merges into `settings`. `resetData` (confirm) runs `initialState('sample')`.
  - The `units` list is displayed, not editable.
- Navigation guard: `nav()` and `setRole()` ask for a "Discard unsaved changes?" confirm when `formDirty`, which compares `form.values` with `form.original` as JSON. A `beforeunload` guard fires when drafts exist.
- Role switch (`setRole`) clears another driver's draft and leaves a detail screen the new role may not see.

## 3. Data objects

- **Customer**: `{id:'c1'…, name, contact, phone, address}`. There are 7 samples. `c7` has a deliberately long name.
- **Product**: `{id:'p1'…, name, active:boolean, units:[{unit, price:number|null}]}`.
  - Prices are JS floats such as `4.5` or `2.25`. `null` means "Price on invoice".
  - There are 17 samples, sorted by name. `p17` Plantains is `active:false`.
- **Units**: `settings.units = ['lb','kg','each','dozen','bunch','hand','bag','box','case']`. There is no conversion.
- **Line**:
  - Fields: `{id, productId|null, desc, qty:string, unit, price:string, priceTouched?, zeroOk?, unitNote?}`.
  - **qty and price are strings** while editing, for example `'12.5'` and `'4.50'`.
  - On issue, `qty` is normalised with `String(parseFloat)` and `price` with `parseFloat().toFixed(2)`.
- **Draft**:
  - Fields: `{id:'DRF-XXXX', status:'draft', driver, customerId, date:'YYYY-MM-DD', dateText:'DD/MM/YYYY', numberOverride, overrideOpen, lines, sync, updatedAt, conflict?:{by, at, lines}}`.
  - The id comes from `newDraftId()`: `'DRF-'` plus 4 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`. Before the first edit the id is `'tmp'`.
- **Issued invoice**:
  - Fields: `{id, draftId, number, manualNumber, status:'issued', driver, customerId, customer:{name, contact, phone, address} (snapshot), business:{name, address, phone, email} (snapshot via bizSnap), date, issuedAt:ISO, prints:int, printStatus, printLog:[{at, printer, result}], sync, qbManual:{at, by}|null, lines}`.
  - `printStatus` is one of `not_printed`, `printed`, `uncertain`, `failed`.
  - `printLog` `result` is one of `printed`, `success`, `unavailable`, `uncertain`, `confirmed`, `not_printed`.
  - `sync` is one of `synced`, `pending`, `failed`, `conflict`.
- **Sample invoices**:
  - INV-00117 to INV-00128, except that INV-00129 does not exist.
  - The sample `issuedAt` values carry the `-04:00` offset.
  - Most have `qbManual` set. i128, i127 and i123 do not.
  - i123 has 14 lines and is `not_printed`.
  - There is also one draft, `DRF-2Q7M` (d2, c5).
- **Business settings** (`defaultSettings`): `{name:'[Business name]', address, phone, email ('… to be confirmed'), prefix:'INV-', nextSeq:'129' (string), units}`.
- **Printers** (`PRINTERS`): `pA` "Demo printer A" and `pB` "Demo printer B", both with the note "Illustrative 80 mm roll printer". `lastPrinter` is remembered.
- **Sync status**: per record `sync`, plus `state.pending` (offline customer, product and settings saves), `scenario`, `recovering`, `saving` and `loadPartial`. The aggregate is built in `renderVals` (`syncS` with tones green, neutral, amber, red).
- **Money**:
  - Integer cents. `cents(l) = round(round(qty*1000) * round(price*100) / 1000)`, which rounds the line half-up to the cent.
  - `total` adds up only valid lines (`lineOk`).
  - `num(c)` formats `c/100` with en-US 2 decimals. `money(c)` returns `'BBD ' + num`.
  - Currency is hard-coded to BBD.
- **Validation** (`qtyErr`, `priceErr`):
  - No negatives.
  - qty: up to 3 decimals, more than 0, at most 99999.
  - price: up to 2 decimals, at most 999999. A price of 0 needs `zeroOk`.
- **Invoice numbers** (`autoNumber(s)`):
  - Format is `prefix + String(n).padStart(5,'0')`, starting at `nextSeq`, which is parsed and falls back to 1.
  - Numbers already used by any invoice are skipped (case-insensitive).
  - A number is assigned **only on issue**. `nextSeq` advances only on the auto path.
  - The manual override is owner only, at most 20 characters, and unique among issued invoices (`overrideErr`).
  - There is one shared sequence for both drivers (a demo assumption).
- **Dates**:
  - Date-only strings. `TZ = 'America/Barbados'`. `today()` uses Intl.
  - `parseDate` accepts ISO, D/M/Y (also with `.`, `-` or a space as separator, and 2-digit years) and month names. Years must be 2000–2099.
  - Display uses `dateLabel` ("16 Oct 2026"), `dateWritten` ("Friday 16 October 2026") and `dateTime` ("… , HH:MM AST").
- **Other state**: `uid(p)` = prefix + `Date.now()` in base 36 + a counter. There is no `localStorage` or `fetch`. All state is in memory in the component.

## 4. Simulated behaviours (must NOT be ported as real success states)

- **Printing**:
  - `sendPrint` is a 1200ms `setTimeout`. The outcome comes from the presenter's `printOutcome` (success, unavailable or uncertain).
  - `pB` always fails as "unavailable".
  - The copy reads "Demo only… No real printer was used", printer status "Ready (demo)" or "May be off (demo)", and log results "Printed (demo)".
- **Sync / save**:
  - `flashSaving` (650ms) and `statusFields` show "Saved (simulated)". The sync text says "saved to the shared system (simulated)".
  - Retry (`retrySaves`) and reconnect (a 1000ms `recT` timer) flip `pending` and `failed` to `synced` unconditionally.
- **Scenarios** (`setScenario`): `normal`, `saving`, `loading`, `offline`, `partial` (marks one record `sync:'failed'`) and `conflict`.
  - `makeConflict` fabricates "Office computer (Owner)" edits: the first line's qty +2 and an added Limes line.
  - The offline scenario blocks issuing (`offlineIssue`). "Check connection" is only a toast.
  - The loading-scenario Retry and `retryLoad` just reset the scenario.
- **QuickBooks**:
  - No integration. The status field shows "QuickBooks transfer: Not configured · future phase".
  - `qbManual` is a manual bookkeeping tick ("nothing was transferred").
  - The Future-phase tab has fictional `futureOutcomes`, `futureMapping` (EXT-SAMPLE-0001, CUST-SAMPLE-014, ITEM-SAMPLE-…) and `futureQueue`. These are illustration only.
- **Auth / roles**: sign-in is not real. Roles come from the presenter switcher.
- **Data**: sample data comes from the "Sample" and "First use" (`dataMode` = `empty`) modes. It resets on reload.
- **Presenter content**: "Prototype · Sample data" chips and the Invoice Document's "Sample invoice · prototype · not a real document" banner and footer.
- **Recent shortcuts**: "Recent" customers and products are labelled "Proposed".
- **Dead state**: `simFail` / `toggleSimFail` exist in the state and view-model but are not referenced in the template.
- **Paper sizes**: "80 mm roll (illustrative)" and "Full page". The UI says paper size is not confirmed.

## 5. Visual tokens

Fonts:
- **Public Sans** (400, 500, 600, 700) is the UI and document font.
- **IBM Plex Mono** (400, 500) is used for uppercase labels and chips: Presenter controls, Prototype, Draft preview.
- Both load from Google Fonts. The document uses `font-variant-numeric: tabular-nums`.

Colours:
- **Surfaces**:
  - Page `#E7E3D9`; app frame `#F5F2EA`; cards and sidebar `#FFFFFF`.
  - Preview pane `#EEEAE1`.
  - Subtle fill `#FAF8F3`, plus `#F5F2EA` and `#F1EEE6` on hover.
- **Text**: ink `#1C1F1A`; muted `#5D6158`.
- **Borders**: card `#DDD8CC`; input `BD = #C9C3B5`; divider `#EEEAE1`; document divider `#E6E1D6`.
- **Forest green**:
  - Primary button `#2D5E3E`, hover and active `#234C31`.
  - Green text `#1F4A2E`; green tint `#E7EFE6`.
  - Selected row `#F1F6F0`; borders `#CFE0D1` and `#B9C9BB`; ring `#BFD3C1`.
  - Focus outline `3px #8DB296`.
- **Amber (warning)**: text `#7A4F0E` and `#5C3B08`; background `#F6EDDA`; border `#E6D3AE`; dot `#B7832B`; conflict border `#C9973A`.
- **Red (error)**: `RED = #A3352A`; dark `#8E2C22`; background `#FBECE8`; borders `#EBC3BA` and `#E3B3A9`.
- **Neutral pill**: background `#EEEAE1`, foreground `#5D6158`, dot `#9A9C93`.
- **Status mapping** (`rowStatus`, `pTone`):

  | Status | Label | Colours |
  |---|---|---|
  | Printed | Printed | `#1F4A2E` on `#E7EFE6` |
  | Uncertain | Check printer | amber |
  | Failed | Print failed | red |
  | Not printed | Not printed | amber in the list; neutral as a pill |
  | Saved | Saved | green |
  | Pending | Waiting | amber |
  | Not saved | Not saved | red |
  | Conflict | Needs review | amber |

  The Issued pill is green and the Draft pill is amber.
- **Presenter chrome**: bar `#23271F`, second row `#2C3127` with border `#3A3F34`; text `#EDEAE2`; muted `#B9B6AC`; button border `#6B705F`.
- **Toast and overlays**: toast `#23271F`; overlay `rgba(28,31,26,0.45)`.

Shape and size:
- **Corner radii**:
  - 8px is the dominant radius (134 uses). 10px is used for cards and alerts.
  - 12px for dialogs and sheets; 999px for pills; 6px and 4px for chips; 50% for dots.
  - The device frame is 24px on phone, 14px on tablet and 0 in fit mode.
- **Touch targets**: most buttons and inputs are 48px tall, others 44px, 52px or 56px.
  - The "Choose customer" button is 64px with a 2px dashed border.
  - The narrow bottom-nav buttons are 52px.
  - Table rows have `min-height` 56px.
- **Type scale**: mostly 15, 13, 16, 14 and 12px. Headings are 17–26px. The issue dialog title is 22px. The document total is 24px.
- **Spacing**: gaps are mostly 8, 10, 12, 4 and 6px. Content padding `pad` is 14px on phone, 22px on tablet portrait and 24px when wide.

Widths:
- `sidebarW` is 264 on desktop and 224 otherwise. `previewW` is 480 on desktop and 380 otherwise.
- Dialogs: issue 500; print, sync and offline 480; confirm 460; customer sheet 640; product sheet 780. The notes drawer is 460.
- `tableCols`, owner: `92px 92px minmax(0,1fr) 66px 104px 74px 102px 92px`.
- `tableCols`, driver: `96px 96px minmax(0,1fr) 110px 80px 110px 100px`.

**Breakpoints and preview presets**:
- Presets (`renderVals.presets`): landscape 1180×820 (the default), portrait 820×1180, phone 390×844, desktop 1440×900, and fit.
- Logic on frame width `fw`:
  - `isPhone` is `fw < 600`.
  - `isWide` is `fw ≥ 1000`. This switches to sidebar plus table plus live preview; narrow layouts get the bottom nav, cards and the Review step.
  - `isDesktop` is `fw ≥ 1300`, which gives the wider sidebar and preview.
- The frame is scaled to fit the stage with `scale = min(1, stageW/fw, stageH/fh)`, measured by a ResizeObserver on `[data-pi-stage]`.

## 6. Invoice Document (`Invoice Document.dc.html`)

A single `doc` prop object, built by `buildDoc(inv, s, isDraft)` in v4. The preview size is 560×640. Layout from top to bottom:

1. **Banners**:
   - `doc.isDraft` shows "Draft preview · not issued" in amber `#7A4F0E` on `#F6EDDA`, in Plex Mono uppercase.
   - `doc.isSample` (set for every issued invoice in v4) shows "Sample invoice · prototype · not a real document" with a dashed border.
2. **Header row (wraps)**:
   - Left: `business.name` (19px bold), `business.address`, `business.phone`, `business.email`.
   - Right: "INVOICE" label, `doc.number` (19px bold), `doc.numberNote` and `doc.dateLabel`.
   - `numberNote` is "Expected · assigned when issued" or "Manual number · not issued" on a draft, "Manual number" on an issued invoice with a manual number, and empty otherwise.
3. **Parties row**:
   - "Bill to": `customer.name`, then "`contact` · `phone`", then `address`. When there is no customer it shows "No customer selected" in red `#A3352A`.
   - "Delivered by": `doc.driverLabel` (Driver 1 or Driver 2).
4. **Line table**:
   - Header: Item | Qty / unit (68px) | Unit price (64px) | Amount (78px). The header has a 1px `#1C1F1A` bottom border.
   - Rows show `ln.desc` (with " (no charge)" appended for zero-price lines), `ln.qtyLabel` ("12.5 lb"), `ln.priceLabel` and `ln.amountLabel`. Invalid values show "—". Price and amount have no currency symbol.
   - With no lines it shows "No items added yet".
5. **Total**: "Total" with `doc.totalLabel` ("BBD 99.00", 24px bold), then "Prices in {currency}" (BBD). There is no tax, discount or subtotal row.
6. **Footer** (Plex Mono 11px):
   - `doc.footer` is either "Draft DRF-XXXX · not an issued invoice" or "Issued {dd Mon yyyy, HH:MM AST}".
   - Next to it, the fixed text "Prototype · fictional sample data".

Container styling: `#FFFFFF` background, 24px padding, 20px gap, 1px `#DDD8CC` border, 8px radius, Public Sans with tabular numbers. In the print preview the container's max-width is 302px (80 mm roll, illustrative) or 680px (full page).

Issued documents render from the snapshot fields `inv.customer` and `inv.business`, so later catalog or settings edits do not change them. Drafts render from live customers and `settings`.
