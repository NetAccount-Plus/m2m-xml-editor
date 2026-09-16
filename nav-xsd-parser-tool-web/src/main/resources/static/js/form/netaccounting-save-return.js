/**
 * NetAccounting integrációhoz tartozó „Mentés és visszatérés” gomb.
 *
 * Első körben csak az m2m-service oldali UI-t készíti elő. A gomb állapota
 * követi a meglévő gyorsmentés gomb állapotát, és kattintáskor egy saját
 * eseményt küld. A tényleges NetAccounting visszatérési folyamatot a következő
 * integrációs lépés köti majd rá erre az eseményre.
 */

const SAVE_RETURN_EVENT = 'nav:netaccounting-save-return';

export function initNetAccountingSaveReturnButton() {
  const toolbar = document.querySelector('.xml-toolbar-buttons-inline');
  const quickSaveButton = document.getElementById('quickSaveXmlFileButton');

  if (!toolbar || !quickSaveButton || document.getElementById('netAccountingSaveReturnButton')) {
    return;
  }

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

  const syncDisabledState = () => {
    button.disabled = quickSaveButton.disabled;
    button.setAttribute('aria-disabled', String(button.disabled));
  };

  syncDisabledState();

  const stateObserver = new MutationObserver(syncDisabledState);
  stateObserver.observe(quickSaveButton, {
    attributes: true,
    attributeFilter: ['disabled']
  });

  button.addEventListener('click', () => {
    if (button.disabled) {
      return;
    }

    window.dispatchEvent(new CustomEvent(SAVE_RETURN_EVENT, {
      detail: {
        source: 'form-toolbar'
      }
    }));
  });

  quickSaveButton.insertAdjacentElement('afterend', separator);
  separator.insertAdjacentElement('afterend', button);
}
