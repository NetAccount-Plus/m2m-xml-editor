/**
 * NetAccounting integrációhoz tartozó „Mentés és visszatérés” gomb.
 *
 * NetAccounting session módban az editor csak szerkeszt és validál; a NAV M2M
 * beküldést a NetAccounting végzi. Ezért az editor saját M2M/online műveletei
 * rejtve maradnak, és a visszatérési gomb állapota nem függ a normál gyorsmentéstől.
 */

const SAVE_RETURN_EVENT = 'nav:netaccounting-save-return';

function getEditorSessionId() {
  return String(new URLSearchParams(window.location.search).get('editorSessionId') || '').trim();
}

export function isNetAccountingEditorSession() {
  return !!getEditorSessionId();
}

function hideEditorM2mControls() {
  [
    'm2mSubmitDropdown',
    'm2mValidationDropdown',
    'm2mCalculationDropdown',
    'm2mAttachmentInput'
  ].forEach(id => {
    const element = document.getElementById(id);
    if (element) {
      element.hidden = true;
      element.style.display = 'none';
      element.setAttribute('aria-hidden', 'true');
    }
  });
}

export function initNetAccountingSaveReturnButton() {
  if (!isNetAccountingEditorSession()) {
    return;
  }

  hideEditorM2mControls();

  const toolbar = document.querySelector('.xml-toolbar-buttons-inline');
  const quickSaveButton = document.getElementById('quickSaveXmlFileButton');

  if (!toolbar || !quickSaveButton || document.getElementById('netAccountingSaveReturnButton')) {
    return;
  }

  document.body.classList.add('netaccounting-editor-session');

  const separator = document.createElement('span');
  separator.className = 'form-toolbar-separator netaccounting-save-return-separator';
  separator.setAttribute('aria-hidden', 'true');

  const button = document.createElement('button');
  button.type = 'button';
  button.id = 'netAccountingSaveReturnButton';
  button.className = 'primary mini-button toolbar-text-action netaccounting-save-return-button';
  button.title = 'Az aktuális XML mentése és visszatérés a NetAccounting rendszerbe';
  button.setAttribute('aria-label', 'Mentés és visszatérés a NetAccounting rendszerbe');
  button.innerHTML = '<svg class="toolbar-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M19 21H5a2 2 0 0 1-2-2V7l4-4h9l5 5v11a2 2 0 0 1-2 2Z"/><path d="M17 21v-8H7v8"/><path d="M7 3v5h8"/><path d="M10 12 5 17l5 5"/><path d="M5 17h8"/></svg><span>Mentés és visszatérés</span>';

  button.disabled = false;
  button.setAttribute('aria-disabled', 'false');

  button.addEventListener('click', () => {
    window.dispatchEvent(new CustomEvent(SAVE_RETURN_EVENT, {
      detail: {
        source: 'form-toolbar',
        editorSessionId: getEditorSessionId()
      }
    }));
  });

  quickSaveButton.insertAdjacentElement('afterend', separator);
  separator.insertAdjacentElement('afterend', button);
}
