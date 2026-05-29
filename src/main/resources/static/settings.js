function updateAllocationTotal() {
    const targets = Array.from(document.querySelectorAll('input[name="targets"]')).map(input => parseFloat(input.value) || 0.0);
    const total = targets.reduce((sum, val) => sum + val, 0.0);
    const totalDisplay = document.getElementById('total-allocated-display');
    totalDisplay.textContent = 'Total: ' + total.toFixed(2) + '%';
    
    const saveButton = document.getElementById('save-button');
    const isValid = Math.abs(total - 100.0) <= 0.01;
    
    const symbols = Array.from(document.querySelectorAll('input[name="symbols"]')).map(input => input.value.toUpperCase());
    const hasUsd = symbols.includes('USD');
    
    if (isValid && hasUsd) {
        totalDisplay.className = 'status-badge live';
        saveButton.removeAttribute('disabled');
    } else {
        totalDisplay.className = 'status-badge delayed';
        saveButton.setAttribute('disabled', 'true');
    }
}

function addAssetRow() {
    const symbolInput = document.getElementById('new-symbol-input');
    const symbol = symbolInput.value.trim().toUpperCase();
    if (!symbol) return;
    
    const existingSymbols = Array.from(document.querySelectorAll('input[name="symbols"]')).map(input => input.value.toUpperCase());
    if (existingSymbols.includes(symbol)) {
        alert('Symbol already exists');
        return;
    }
    
    const container = document.getElementById('allocations-container');
    const template = document.getElementById('allocation-row-template');
    const clone = template.content.cloneNode(true);
    
    clone.querySelector('.symbol-label').textContent = symbol;
    clone.querySelector('input[name="symbols"]').value = symbol;
    clone.querySelector('input[name="targets"]').value = "0.0";
    
    container.appendChild(clone);
    symbolInput.value = '';
    updateAllocationTotal();
}

updateAllocationTotal();
