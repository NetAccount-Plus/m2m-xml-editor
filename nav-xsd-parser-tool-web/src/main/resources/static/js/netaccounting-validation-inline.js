/**
 * NetAccounting-specific presentation for XSD validation results.
 *
 * Reuses the existing XSD validation drawer DOM and data. The technical
 * summary is hidden, while the user-facing status and real validation errors
 * are moved inline above the form toolbar.
 */

const STYLE_ID = 'netaccounting-inline-xsd-validation-style';

function installStyles(){
  if(document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawerTab {
      display: none !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation {
      position: static !important;
      inset: auto !important;
      width: auto !important;
      height: auto !important;
      max-width: none !important;
      max-height: none !important;
      flex: 0 0 auto !important;
      display: block !important;
      margin: 0 !important;
      border: 0 !important;
      border-bottom: 1px solid var(--na-border, #c7d1d5) !important;
      border-radius: 0 !important;
      background: var(--na-surface-raised, #fafbfb) !important;
      box-shadow: none !important;
      overflow: visible !important;
      transform: none !important;
      z-index: auto !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-drawer-header {
      min-height: 0 !important;
      padding: 8px 12px !important;
      border: 0 !important;
      background: transparent !important;
      cursor: default !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-drawer-header .eyebrow,
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-drawer-header h2,
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation #formXsdValidationDrawerClose,
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-drawer-footer,
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-popup-summary-scroll {
      display: none !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation #formXsdValidationDrawerStatus {
      display: inline-flex !important;
      align-items: center;
      gap: 6px;
      margin: 0 !important;
      padding: 5px 9px !important;
      border: 1px solid var(--na-border, #c7d1d5) !important;
      border-radius: 6px !important;
      background: var(--na-surface, #f2f5f6) !important;
      color: var(--na-text, #24343b) !important;
      font-size: 12px !important;
      font-weight: 700 !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation #formXsdValidationDrawerStatus.ok,
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation.na-validation-ok #formXsdValidationDrawerStatus {
      border-color: #a8c9b7 !important;
      background: #e7f2ec !important;
      color: #315f4d !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation #formXsdValidationDrawerStatus.error,
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation.na-validation-error #formXsdValidationDrawerStatus {
      border-color: #ddb2b2 !important;
      background: #f8eaea !important;
      color: #a64040 !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation #formXsdValidationDrawerStatus.warning,
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation.na-validation-warning #formXsdValidationDrawerStatus {
      border-color: #dcc59d !important;
      background: #fbf3e5 !important;
      color: #94631e !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-drawer-content {
      padding: 0 12px 9px !important;
      overflow: visible !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation #formXsdValidationDrawerMessage {
      margin: 0 !important;
      padding: 7px 9px !important;
      border-radius: 6px !important;
      font-size: 12px !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation.na-validation-ok .xpath-popup-errors-scroll {
      display: none !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-popup-errors-scroll {
      max-height: 230px !important;
      margin-top: 8px !important;
      overflow: auto !important;
      border: 1px solid var(--na-border, #c7d1d5) !important;
      border-radius: 6px !important;
      background: #fff !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-popup-errors-table {
      width: 100% !important;
      margin: 0 !important;
      font-size: 12px !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-popup-errors-table th,
    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation .xpath-popup-errors-table td {
      padding: 6px 8px !important;
    }

    body.nav-application-theme[data-initial-tab="formTab"] #formXsdValidationDrawer.na-inline-xsd-validation tr.na-technical-validation-row {
      display: none !important;
    }
  `;
  document.head.appendChild(style);
}

function isTechnicalRow(row){
  const text = String(row?.textContent || '').toUpperCase();
  return text.includes('SCHEMA_RESOLVED') || text.includes('INFORMÁCIÓ') || text.includes('INFORMATION');
}

function updateInlineState(drawer){
  if(!drawer) return;

  const status = drawer.querySelector('#formXsdValidationDrawerStatus');
  const statusText = String(status?.textContent || '').toUpperCase();
  const statusClass = String(status?.className || '').toLowerCase();
  const ok = statusClass.includes('ok') || statusText.includes('NINCS XSD HIBA') || statusText === 'OK';
  const warning = statusClass.includes('warning') || statusText.includes('MEGSZAK');

  drawer.classList.toggle('na-validation-ok', ok);
  drawer.classList.toggle('na-validation-warning', !ok && warning);
  drawer.classList.toggle('na-validation-error', !ok && !warning);

  drawer.querySelectorAll('#formXsdValidationErrorsBody tr').forEach(row => {
    row.classList.toggle('na-technical-validation-row', isTechnicalRow(row));
  });
}

function attachInlineDrawer(){
  const drawer = document.getElementById('formXsdValidationDrawer');
  if(!drawer) return false;

  const splitCard = document.querySelector('#formTab .split-card');
  const toolbar = document.getElementById('formUnifiedToolbar');
  if(!splitCard || !toolbar) return false;

  if(drawer.parentElement !== splitCard){
    splitCard.insertBefore(drawer, toolbar);
  }
  drawer.classList.add('na-inline-xsd-validation', 'open');
  updateInlineState(drawer);
  return true;
}

export function initNetAccountingInlineValidation(){
  if(document.body?.dataset?.initialTab !== 'formTab') return;
  installStyles();

  let refreshQueued = false;
  const refresh = () => {
    refreshQueued = false;
    attachInlineDrawer();
  };

  const observer = new MutationObserver(() => {
    if(refreshQueued) return;
    refreshQueued = true;
    queueMicrotask(refresh);
  });

  observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  attachInlineDrawer();
}
