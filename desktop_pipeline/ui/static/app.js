document.addEventListener('DOMContentLoaded', () => {
    // Navigation Tabs
    const navButtons = document.querySelectorAll('.nav-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    navButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const tabId = btn.getAttribute('data-tab');
            
            navButtons.forEach(b => b.classList.remove('active'));
            tabContents.forEach(c => c.classList.remove('active'));

            btn.classList.add('active');
            document.getElementById(`tab-${tabId}`).classList.add('active');
        });
    });

    // Subtabs in Single View
    const subTabButtons = document.querySelectorAll('.tab-sub-btn');
    const subTabContents = document.querySelectorAll('.subtab-content');

    subTabButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const subTabId = btn.getAttribute('data-subtab');

            subTabButtons.forEach(b => b.classList.remove('active'));
            subTabContents.forEach(c => c.classList.remove('active'));

            btn.classList.add('active');
            document.getElementById(`subtab-${subTabId}`).classList.add('active');
        });
    });

    // Single Enrichment Execution
    const btnRun = document.getElementById('btn-run');
    const inputFolder = document.getElementById('folder-path');
    const terminal = document.getElementById('terminal-output');
    const btnClearLogs = document.getElementById('btn-clear-logs');
    let sseSource = null;

    btnClearLogs.addEventListener('click', () => {
        terminal.innerHTML = '<div class="terminal-line system">Logs cleared.</div>';
    });

    btnRun.addEventListener('click', async () => {
        const folderPath = inputFolder.value.trim();
        if (!folderPath) {
            alert('Please specify a folder path.');
            return;
        }

        appendLog('Starting enrichment process request...', 'system');
        btnRun.disabled = true;

        try {
            const res = await fetch('/api/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ folder_path: folderPath })
            });
            const data = await res.json();
            
            if (data.error) {
                appendLog(`Error: ${data.error}`, 'error');
                btnRun.disabled = false;
                return;
            }

            const taskId = data.task_id;
            listenToLogs(taskId, folderPath);
        } catch (err) {
            appendLog(`Connection Failed: ${err.message}`, 'error');
            btnRun.disabled = false;
        }
    });

    function appendLog(text, type = 'info') {
        const line = document.createElement('div');
        line.className = `terminal-line ${type}`;
        line.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
        terminal.appendChild(line);
        terminal.scrollTop = terminal.scrollHeight;
    }

    function listenToLogs(taskId, folderPath) {
        if (sseSource) sseSource.close();

        sseSource = new EventSource(`/api/run/${taskId}/stream`);

        sseSource.onmessage = (event) => {
            const data = JSON.parse(event.data);
            if (data.log) {
                let logType = 'info';
                if (data.log.includes('[ERROR]')) logType = 'error';
                else if (data.log.includes('[WARNING]')) logType = 'warning';
                else if (data.log.includes('*** Enrichment Successful! ***') || data.log.includes('Saved manifest')) logType = 'success';
                
                appendLog(data.log, logType);
            }

            if (data.done) {
                sseSource.close();
                btnRun.disabled = false;
                if (data.success) {
                    appendLog('Process completed successfully!', 'success');
                    loadResults(folderPath);
                } else {
                    appendLog(`Process exited with failure.`, 'error');
                }
            }
        };

        sseSource.onerror = () => {
            appendLog('EventSource stream interrupted or finished.', 'system');
            sseSource.close();
            btnRun.disabled = false;
        };
    }

    // Load & Render Results
    let currentResults = null;

    async function loadResults(folderPath) {
        try {
            const res = await fetch(`/api/results?folder=${encodeURIComponent(folderPath)}`);
            if (!res.ok) throw new Error('Failed to retrieve results payload.');
            const data = await res.json();
            currentResults = data;
            
            renderSummary(data);
            renderDetailedTables(data);
            
            document.getElementById('results-detail-card').classList.remove('hidden');
        } catch (err) {
            appendLog(`Results loading error: ${err.message}`, 'error');
        }
    }

    function renderSummary(data) {
        const enrichment = data.enrichment;
        const summaryDiv = document.getElementById('summary-content');
        
        const docType = enrichment.documentType || 'Unknown';
        const industry = enrichment.classifiedIndustry || 'Not classified';
        const reviewCount = enrichment.criticalFieldsNeedingReview ? enrichment.criticalFieldsNeedingReview.length : 0;
        
        let estValue = 'N/A';
        if (enrichment.extractedSchema && enrichment.extractedSchema.financial_criteria) {
            const val = enrichment.extractedSchema.financial_criteria.estimated_tender_value_zar;
            if (val && typeof val === 'number') {
                estValue = new Intl.NumberFormat('en-ZA', { style: 'currency', currency: 'ZAR' }).format(val);
            }
        }

        summaryDiv.innerHTML = `
            <div class="summary-metrics">
                <div class="metric-box">
                    <div class="metric-value">${docType}</div>
                    <div class="metric-label">Document Type</div>
                </div>
                <div class="metric-box">
                    <div class="metric-value" style="font-size:15px; word-break:break-word;">${industry}</div>
                    <div class="metric-label">Classified Industry</div>
                </div>
                <div class="metric-box">
                    <div class="metric-value">${estValue}</div>
                    <div class="metric-label">Est. Value</div>
                </div>
            </div>
            <div style="margin-top: 20px;">
                <p><strong>Critical Fields Requiring Manual Review:</strong> <span class="badge ${reviewCount > 0 ? 'badge-danger' : 'badge-success'}">${reviewCount}</span></p>
                ${reviewCount > 0 ? `<ul style="margin: 10px 0 0 20px; font-size:14px; color:var(--text-secondary);">
                    ${enrichment.criticalFieldsNeedingReview.map(f => `<li>${f.field}: ${f.reason}</li>`).join('')}
                </ul>` : ''}
            </div>
        `;
    }

    function renderDetailedTables(data) {
        const enrichment = data.enrichment;
        const schema = enrichment.extractedSchema || {};
        
        const metadataBody = document.getElementById('table-metadata-body');
        const complianceBody = document.getElementById('table-compliance-body');
        const contactsPre = document.getElementById('contacts-raw');

        metadataBody.innerHTML = '';
        complianceBody.innerHTML = '';
        
        // Populate Metadata Subtab
        const metadataFields = [
            { label: 'Tender Title', val: schema.tender_metadata?.tender_title, field: 'tender_title' },
            { label: 'Tender Reference', val: schema.tender_metadata?.tender_reference_number, field: 'tender_reference_number' },
            { label: 'Issuing Institution', val: schema.tender_metadata?.issuing_institution, field: 'issuing_institution' },
            { label: 'Province', val: schema.tender_metadata?.geographic_locality?.province, field: 'geographic_locality.province' },
            { label: 'Briefing Date', val: schema.critical_dates?.compulsory_briefing?.briefing_date_time, field: 'compulsory_briefing.briefing_date_time' },
            { label: 'Closing Date', val: schema.critical_dates?.closing_date_time, field: 'closing_date_time' }
        ];

        metadataFields.forEach(f => {
            const row = createTableRow(f.label, f.val, enrichment.audits && enrichment.audits[f.field], enrichment.evidenceMap && enrichment.evidenceMap[f.field]);
            metadataBody.appendChild(row);
        });

        // Populate Compliance & Financial
        const complianceFields = [
            { label: 'Estimated ZAR Value', val: schema.financial_criteria?.estimated_tender_value_zar, field: 'estimated_tender_value_zar' },
            { label: 'CIDB Minimum Grade', val: schema.industry_credentials?.cidb_requirements?.minimum_grade, field: 'cidb_requirements.minimum_grade' },
            { label: 'Score System Applicable', val: schema.preferential_procurement?.scoring_system_applicable, field: 'scoring_system_applicable' },
            { label: 'MBD 1 Required', val: schema.statutory_forms?.mbd_forms?.mbd_1_required ?? false, field: 'statutory_forms_mbd_forms_mbd_1_required' },
            { label: 'MBD 4 Required', val: schema.statutory_forms?.mbd_forms?.mbd_4_required ?? false, field: 'statutory_forms_mbd_forms_mbd_4_required' },
            { label: 'MBD 6.1 Required', val: schema.statutory_forms?.mbd_forms?.mbd_6_1_required ?? false, field: 'statutory_forms_mbd_forms_mbd_6_1_required' },
            { label: 'MBD 15 Required', val: schema.statutory_forms?.mbd_forms?.mbd_15_required ?? false, field: 'statutory_forms_mbd_forms_mbd_15_required' },
            { label: 'SBD 1 Required', val: schema.statutory_forms?.sbd_forms?.sbd_1_required ?? false, field: 'statutory_forms_sbd_forms_sbd_1_required' },
            { label: 'SBD 4 Required', val: schema.statutory_forms?.sbd_forms?.sbd_4_required ?? false, field: 'statutory_forms_sbd_forms_sbd_4_required' },
            { label: 'SBD 6.1 Required', val: schema.statutory_forms?.sbd_forms?.sbd_6_1_required ?? false, field: 'statutory_forms_sbd_forms_sbd_6_1_required' }
        ];

        complianceFields.forEach(f => {
            const row = createTableRow(f.label, f.val, enrichment.audits && enrichment.audits[f.field], enrichment.evidenceMap && enrichment.evidenceMap[f.field]);
            complianceBody.appendChild(row);
        });

        // Contact details representation
        contactsPre.textContent = JSON.stringify(schema.contact_details || {}, null, 2);
    }

    function createTableRow(label, val, audit, evidence) {
        const tr = document.createElement('tr');
        
        const tdLabel = document.createElement('td');
        tdLabel.innerHTML = `<strong>${label}</strong>`;
        
        const tdValue = document.createElement('td');
        tdValue.textContent = val !== undefined && val !== null ? val : 'Not found';

        const tdAudit = document.createElement('td');
        if (audit) {
            const status = audit.status || 'WARNING';
            let badgeClass = 'badge-danger';
            if (status === 'DONE') {
                badgeClass = evidence ? 'badge-success' : 'badge-warning';
            }
            tdAudit.innerHTML = `<span class="badge ${badgeClass}">${status}</span>`;
        } else {
            tdAudit.innerHTML = `<span class="badge">N/A</span>`;
        }

        const tdEvidence = document.createElement('td');
        tdEvidence.className = 'evidence-cell';
        tdEvidence.textContent = evidence || 'No direct snippet evidence found';
        tdEvidence.title = evidence || '';

        tr.appendChild(tdLabel);
        tr.appendChild(tdValue);
        tr.appendChild(tdAudit);
        tr.appendChild(tdEvidence);

        return tr;
    }

    // Exporting JSON
    document.getElementById('btn-export-json').addEventListener('click', () => {
        if (!currentResults) return;
        const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(currentResults, null, 2));
        const downloadAnchor = document.createElement('a');
        downloadAnchor.setAttribute("href", dataStr);
        downloadAnchor.setAttribute("download", `tender_enrichment_${Date.now()}.json`);
        document.body.appendChild(downloadAnchor);
        downloadAnchor.click();
        downloadAnchor.remove();
    });

    // Batch Processing Tab Logic
    const btnScanBatch = document.getElementById('btn-scan-batch');
    const btnListFirebase = document.getElementById('btn-list-firebase');
    const btnDownloadFirebase = document.getElementById('btn-download-firebase');
    const btnRunBatch = document.getElementById('btn-run-batch');
    const btnPullFirebase = document.getElementById('btn-pull-firebase');
    const inputBatchRoot = document.getElementById('batch-root-path');
    const batchListCard = document.getElementById('batch-list-card');
    const batchTableBody = document.getElementById('batch-table-body');
    const firebaseLogCard = document.getElementById('firebase-log-card');
    const firebaseTerminalOutput = document.getElementById('firebase-terminal-output');
    const firebaseListCard = document.getElementById('firebase-list-card');
    const firebaseListTableBody = document.getElementById('firebase-list-table-body');
    
    let scannedFolders = [];
    let firebaseFolderIds = [];
    let firebaseSse = null;

    btnScanBatch.addEventListener('click', async () => {
        const rootPath = inputBatchRoot.value.trim();
        if (!rootPath) {
            alert('Please specify batch root folder.');
            return;
        }

        try {
            const res = await fetch(`/api/batch?root=${encodeURIComponent(rootPath)}`);
            const data = await res.json();
            
            if (data.error) {
                alert(`Error scanning: ${data.error}`);
                return;
            }

            scannedFolders = data.subfolders;
            renderBatchTable(scannedFolders);
            batchListCard.classList.remove('hidden');
            btnRunBatch.classList.remove('hidden');
        } catch (err) {
            alert(`Scan failed: ${err.message}`);
        }
    });

    btnListFirebase.addEventListener('click', async () => {
        try {
            const res = await fetch('/api/firebase/list');
            const data = await res.json();
            if (data.error) {
                alert(`Unable to list Firebase folders: ${data.error}`);
                return;
            }

            firebaseFolderIds = data.tender_ids || [];
            firebaseListTableBody.innerHTML = '';

            if (firebaseFolderIds.length === 0) {
                firebaseListTableBody.innerHTML = '<tr><td colspan="3" style="text-align:center;">No Firebase folders found.</td></tr>';
                firebaseListCard.classList.remove('hidden');
                btnDownloadFirebase.disabled = true;
                return;
            }

            firebaseFolderIds.forEach(id => {
                const tr = document.createElement('tr');
                const tdSelect = document.createElement('td');
                tdSelect.innerHTML = `<input type="checkbox" class="firebase-folder-chk" data-id="${id}" checked>`;
                const tdId = document.createElement('td');
                tdId.textContent = id;
                const tdStatus = document.createElement('td');
                tdStatus.textContent = 'remote';
                tr.appendChild(tdSelect);
                tr.appendChild(tdId);
                tr.appendChild(tdStatus);
                firebaseListTableBody.appendChild(tr);
            });

            firebaseListCard.classList.remove('hidden');
            btnDownloadFirebase.disabled = firebaseFolderIds.length === 0;
        } catch (err) {
            alert(`Failed to list Firebase folders: ${err.message}`);
        }
    });

    btnDownloadFirebase.addEventListener('click', async () => {
        const selected = Array.from(document.querySelectorAll('.firebase-folder-chk:checked')).map(chk => chk.getAttribute('data-id'));
        if (selected.length === 0) {
            alert('Select at least one Firebase tender folder to download.');
            return;
        }

        firebaseLogCard.classList.remove('hidden');
        firebaseTerminalOutput.innerHTML = '';
        const appendFbLog = (text, type = 'info') => {
            const line = document.createElement('div');
            line.className = `terminal-line ${type}`;
            line.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
            firebaseTerminalOutput.appendChild(line);
            firebaseTerminalOutput.scrollTop = firebaseTerminalOutput.scrollHeight;
        };

        appendFbLog(`Downloading ${selected.length} Firebase folder(s)...`, 'system');
        btnDownloadFirebase.disabled = true;

        try {
            const res = await fetch('/api/firebase/download', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ tender_ids: selected }),
            });
            const data = await res.json();
            if (data.error) {
                appendFbLog(`Error: ${data.error}`, 'error');
                btnDownloadFirebase.disabled = false;
                return;
            }

            if (firebaseSse) firebaseSse.close();
            firebaseSse = new EventSource(`/api/firebase/download/${data.task_id}/stream`);
            firebaseSse.onmessage = (event) => {
                const streamData = JSON.parse(event.data);
                if (streamData.log) {
                    appendFbLog(streamData.log, 'info');
                }
                if (streamData.done) {
                    firebaseSse.close();
                    if (streamData.success) {
                        appendFbLog('Firebase folders downloaded successfully.', 'success');
                        const sep = inputBatchRoot.value.includes('\\') ? '\\' : '/';
                        if (!inputBatchRoot.value.endsWith('firebase_tenders')) {
                            inputBatchRoot.value = inputBatchRoot.value.replace(/[\/\\]$/, '') + sep + 'firebase_tenders';
                        }
                        setTimeout(() => btnScanBatch.click(), 1000);
                    } else {
                        appendFbLog(`Download failed (exit code ${streamData.exit_code}).`, 'error');
                    }
                    btnDownloadFirebase.disabled = false;
                }
            };
            firebaseSse.onerror = () => {
                appendFbLog('Firebase download stream interrupted.', 'error');
                firebaseSse.close();
                btnDownloadFirebase.disabled = false;
            };
        } catch (err) {
            appendFbLog(`Connection failed: ${err.message}`, 'error');
            btnDownloadFirebase.disabled = false;
        }
    });

    btnPullFirebase.addEventListener('click', async () => {
        firebaseLogCard.classList.remove('hidden');
        firebaseTerminalOutput.innerHTML = '';
        const appendFbLog = (text, type = 'info') => {
            const line = document.createElement('div');
            line.className = `terminal-line ${type}`;
            line.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
            firebaseTerminalOutput.appendChild(line);
            firebaseTerminalOutput.scrollTop = firebaseTerminalOutput.scrollHeight;
        };
        
        appendFbLog('Initiating Firebase Storage pull request...', 'system');
        btnPullFirebase.disabled = true;
        
        try {
            const res = await fetch('/api/firebase/pull', { method: 'POST' });
            const data = await res.json();
            
            if (data.error) {
                appendFbLog(`Error: ${data.error}`, 'error');
                btnPullFirebase.disabled = false;
                return;
            }
            
            if (firebaseSse) firebaseSse.close();
            firebaseSse = new EventSource(`/api/firebase/pull/${data.task_id}/stream`);
            
            firebaseSse.onmessage = (event) => {
                const streamData = JSON.parse(event.data);
                if (streamData.log) {
                    appendFbLog(streamData.log, 'info');
                }
                if (streamData.done) {
                    firebaseSse.close();
                    appendFbLog('Successfully synced all folders from Cloud Storage!', 'success');
                    btnPullFirebase.disabled = false;
                    
                    // Auto-fill the path to the newly downloaded directory and scan
                    const sep = inputBatchRoot.value.includes('\\') ? '\\' : '/';
                    if (!inputBatchRoot.value.endsWith('firebase_tenders')) {
                        inputBatchRoot.value = inputBatchRoot.value.replace(/[\/\\]$/, '') + sep + 'firebase_tenders';
                    }
                    setTimeout(() => btnScanBatch.click(), 1000);
                }
            };
            
            firebaseSse.onerror = () => {
                appendFbLog('EventSource stream interrupted.', 'error');
                firebaseSse.close();
                btnPullFirebase.disabled = false;
            };
        } catch (err) {
            appendFbLog(`Connection Failed: ${err.message}`, 'error');
            btnPullFirebase.disabled = false;
        }
    });

    function renderBatchTable(folders) {
        batchTableBody.innerHTML = '';
        if (folders.length === 0) {
            batchTableBody.innerHTML = '<tr><td colspan="4" style="text-align:center;">No child subfolders found.</td></tr>';
            return;
        }

        folders.forEach(f => {
            const tr = document.createElement('tr');
            
            const tdSelect = document.createElement('td');
            tdSelect.innerHTML = `<input type="checkbox" class="batch-select-chk" data-path="${f.path}" ${f.status === 'pending' ? 'checked' : ''}>`;

            const tdName = document.createElement('td');
            tdName.innerHTML = `<strong>${f.name}</strong><br><small style="color:var(--text-secondary);">${f.path}</small>`;

            const tdStatus = document.createElement('td');
            let badgeClass = 'badge-success';
            if (f.status === 'pending') badgeClass = 'badge-warning';
            tdStatus.innerHTML = `<span class="badge ${badgeClass}">${f.status}</span>`;

            const tdActions = document.createElement('td');
            tdActions.innerHTML = `<button class="btn btn-secondary btn-small btn-view-res" data-path="${f.path}" ${f.status === 'enriched' ? '' : 'disabled'}>View Results</button>`;

            tr.appendChild(tdSelect);
            tr.appendChild(tdName);
            tr.appendChild(tdStatus);
            tr.appendChild(tdActions);

            batchTableBody.appendChild(tr);
        });

        // View single result click inside batch
        document.querySelectorAll('.btn-view-res').forEach(btn => {
            btn.addEventListener('click', () => {
                const path = btn.getAttribute('data-path');
                inputFolder.value = path;
                document.querySelector('.nav-btn[data-tab="dashboard"]').click();
                loadResults(path);
            });
        });
    }

    // Run Batch Enrichment Sequentially
    let batchSse = null;
    const progressContainer = document.getElementById('batch-progress-container');
    const progressText = document.getElementById('batch-progress-text');
    const currentFolderText = document.getElementById('batch-current-folder');
    const progressFill = document.getElementById('batch-progress-fill');

    btnRunBatch.addEventListener('click', async () => {
        const selectedCheckboxes = document.querySelectorAll('.batch-select-chk:checked');
        const pathsToEnrich = Array.from(selectedCheckboxes).map(chk => chk.getAttribute('data-path'));
        
        if (pathsToEnrich.length === 0) {
            alert('Please select at least one folder to enrich.');
            return;
        }

        progressContainer.classList.remove('hidden');
        btnRunBatch.disabled = true;
        btnScanBatch.disabled = true;

        try {
            const res = await fetch('/api/batch/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ folder_paths: pathsToEnrich })
            });
            const data = await res.json();
            
            if (data.error) {
                alert(`Error initiating batch: ${data.error}`);
                btnRunBatch.disabled = false;
                btnScanBatch.disabled = false;
                return;
            }

            listenToBatchLogs(data.task_id, pathsToEnrich);
        } catch (err) {
            alert(`Batch trigger failure: ${err.message}`);
            btnRunBatch.disabled = false;
            btnScanBatch.disabled = false;
        }
    });

    function listenToBatchLogs(taskId, paths) {
        if (batchSse) batchSse.close();

        batchSse = new EventSource(`/api/batch/run/${taskId}/stream`);

        batchSse.onmessage = (event) => {
            const data = JSON.parse(event.data);
            
            if (data.batch_progress) {
                const current = data.batch_progress.current;
                const total = data.batch_progress.total;
                const folder = data.batch_progress.folder;
                
                progressText.textContent = `Processing: ${current} / ${total}`;
                currentFolderText.textContent = `| Current: ${folder}`;
                
                const percent = (current / total) * 100;
                progressFill.style.width = `${percent}%`;
            }

            if (data.log) {
                // If dashboard terminal is open, append logs there as well
                appendLog(data.log, 'info');
            }

            if (data.done) {
                batchSse.close();
                progressText.textContent = `Completed batch enrichment!`;
                currentFolderText.textContent = '';
                progressFill.style.width = '100%';
                
                btnRunBatch.disabled = false;
                btnScanBatch.disabled = false;
                
                // Rescan automatically to update status badges
                btnScanBatch.click();
            }
        };

        batchSse.onerror = () => {
            batchSse.close();
            btnRunBatch.disabled = false;
            btnScanBatch.disabled = false;
        };
    }

    // Continuous Automation Tab Logic
    const btnStartAutomation = document.getElementById('btn-start-automation');
    const automationLogCard = document.getElementById('automation-log-card');
    const automationTerminalOutput = document.getElementById('automation-terminal-output');
    let automationSse = null;

    if (btnStartAutomation) {
        btnStartAutomation.addEventListener('click', async () => {
            automationLogCard.classList.remove('hidden');
            automationTerminalOutput.innerHTML = '';
            
            const appendAutoLog = (text, type = 'info') => {
                const line = document.createElement('div');
                line.className = `terminal-line ${type}`;
                line.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
                automationTerminalOutput.appendChild(line);
                automationTerminalOutput.scrollTop = automationTerminalOutput.scrollHeight;
            };
            
            appendAutoLog('Launching Continuous Automation Cycle...', 'system');
            btnStartAutomation.disabled = true;
            btnStartAutomation.textContent = '⏳ Automation Running...';
            
            try {
                const res = await fetch('/api/automation/run', { method: 'POST' });
                const data = await res.json();
                
                if (data.error) {
                    appendAutoLog(`Error: ${data.error}`, 'error');
                    btnStartAutomation.disabled = false;
                    btnStartAutomation.textContent = '▶ Start Automation Cycle';
                    return;
                }
                
                if (automationSse) automationSse.close();
                automationSse = new EventSource(`/api/automation/run/${data.task_id}/stream`);
                
                automationSse.onmessage = (event) => {
                    const streamData = JSON.parse(event.data);
                    if (streamData.log) {
                        let type = 'info';
                        if (streamData.log.includes('[ERROR]')) type = 'error';
                        else if (streamData.log.includes('Successfully')) type = 'success';
                        else if (streamData.log.includes('=======')) type = 'system';
                        
                        appendAutoLog(streamData.log, type);
                    }
                    if (streamData.done) {
                        automationSse.close();
                        appendAutoLog('Continuous Automation Cycle successfully concluded.', 'success');
                        btnStartAutomation.disabled = false;
                        btnStartAutomation.textContent = '▶ Restart Automation Cycle';
                    }
                };
                
                automationSse.onerror = () => {
                    appendAutoLog('Automation stream interrupted.', 'error');
                    automationSse.close();
                    btnStartAutomation.disabled = false;
                    btnStartAutomation.textContent = '▶ Resume Automation Cycle';
                };
            } catch (err) {
                appendAutoLog(`Failed to start automation: ${err.message}`, 'error');
                btnStartAutomation.disabled = false;
                btnStartAutomation.textContent = '▶ Retry Automation';
            }
        });
    }
});
