/**
 * NetAccounting integrációhoz tartozó „Mentés és visszatérés” gomb.
 *
 * NetAccounting session módban az editor csak szerkeszt és validál; a NAV M2M
 * beküldést a NetAccounting végzi. A gomb az aktuális XML-t a rövid életű
 * editor sessionhöz menti, majd sikeres mentésről visszajelzést ad.
 */

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

async function showResult(title, message, variant = 'info') {
  if (typeof window.navInfo === 'function') {
    await window.navInfo({
      eyebrow: 'NetAccounting',
      title,
      message,
      cancelText: 'Rendben',
      variant
    });
    return;
  }
  window.alert(message);
}

async function saveAndReturn(button) {
  const editorSessionId = getEditorSessionId();
  if (!editorSessionId) {
    await showResult('A mentés nem indítható', 'Hiányzik a NetAccounting editor munkamenet azonosítója.', 'error');
    return;
  }

  const xml = window.NavModularActions?.serializeCurrentXml?.();
  if (!String(xml || '').trim()) {
    await showResult('A mentés nem indítható', 'Az aktuális XML tartalom nem érhető el.', 'error');
    return;
  }

  const originalText = button.querySelector('span')?.textContent || 'Mentés és visszatérés';
  button.disabled = true;
  button.setAttribute('aria-disabled', 'true');
  const label = button.querySelector('span');
  if (label) label.textContent = 'Mentés...';

  try {
    const response = await fetch(`/api/netaccounting/editor-sessions/${encodeURIComponent(editorSessionId)}/complete`, {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: {
        'Content-Type': 'application/xml; charset=UTF-8'
      },
      body: xml
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data.message || data.error || 'A NetAccounting XML mentése nem sikerült.');
    }

    document.body.dataset.netAccountingEditorCompleted = 'true';
    await showResult(
      'Az XML mentése sikerült',
      'A módosított XML elmentésre került a NetAccounting editor munkamenethez. A következő lépésben erre kötjük rá a tényleges visszatérést a NetAccounting rendszerbe.',
      'success'
    );
  } catch (error) {
    await showResult('Az XML mentése sikertelen', String(error?.message || error), 'error');
  } finally {
    button.disabled = false;
    button.setAttribute('aria-disabled', 'false');
    if (label) label.textContent = originalText;
  }
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
  button.addEventListener('click', () => saveAndReturn(button));

  quickSaveButton.insertAdjacentElement('afterend', separator);
  separator.insertAdjacentElement('afterend', button);
}
