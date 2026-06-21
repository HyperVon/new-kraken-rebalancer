const updateAllocationTotal = () => {
    const targets = Array.from(document.querySelectorAll('input[name="targets"]')).map(input => parseFloat(input.value) || 0.0);
    const total = targets.reduce((sum, val) => sum + val, 0.0);
    const totalDisplay = document.getElementById('total-allocated-display');
    totalDisplay.textContent = `Total: ${total.toFixed(2)}%`;

    const saveButton = document.getElementById('save-button');
    const isValid = Math.abs(total - 100.0) <= 0.01;

    const symbols = Array.from(document.querySelectorAll('input[name="symbols"]')).map(input => input.value.toUpperCase());
    const hasUsd = symbols.includes('USD');

    const isSuccess = isValid && hasUsd;
    totalDisplay.classList.toggle('live', isSuccess);
    totalDisplay.classList.toggle('delayed', !isSuccess);
    saveButton.disabled = !isSuccess;
};

const addAssetRow = () => {
    const symbolInput = document.getElementById('new-symbol-input');
    const symbol = symbolInput.value.trim().toUpperCase();
    if (!symbol) return;

    const existingSymbols = Array.from(document.querySelectorAll('input[name="symbols"]')).map(input => input.value.toUpperCase());
    if (existingSymbols.includes(symbol)) {
        alert('Symbol already exists');
        return;
    }

    const container = document.getElementById('allocations-container');
    const row = document.createElement('div');
    row.className = 'allocation-edit-row';

    row.innerHTML = `
        <div class="allocation-edit-symbol symbol-label">${symbol}</div>
        <input type="hidden" name="symbols" value="${symbol}">
        <div class="allocation-edit-input-wrapper">
            <input type="number" step="0.1" name="targets" class="input-glass" value="0.0" oninput="updateAllocationTotal()">
            <span class="percent-suffix">%</span>
        </div>
        <button type="button" class="btn btn-danger" onclick="this.closest('.allocation-edit-row').remove(); updateAllocationTotal();">Remove</button>
    `;

    container.appendChild(row);
    symbolInput.value = '';
    updateAllocationTotal();
};

// Export to window scope so they can be called from inline HTML attributes
window.updateAllocationTotal = updateAllocationTotal;
window.addAssetRow = addAssetRow;

updateAllocationTotal();
