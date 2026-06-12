import os
import sys
import json
import uuid
import subprocess
from flask import Flask, jsonify, request, Response, send_from_directory

app = Flask(__name__, static_folder="static")

# In-memory store for active task streams and status
active_tasks = {}

@app.route("/")
def index():
    return send_from_directory("static", "index.html")

@app.route("/api/status")
def status():
    return jsonify({
        "status": "healthy",
        "version": "1.0.0",
        "python_version": sys.version
    })

@app.route("/api/run", methods=["POST"])
def run_enrichment():
    data = request.json or {}
    folder_path = data.get("folder_path")
    if not folder_path:
        return jsonify({"error": "folder_path is required"}), 400
    
    if not os.path.exists(folder_path):
        return jsonify({"error": f"Folder path does not exist: {folder_path}"}), 400
        
    task_id = str(uuid.uuid4())
    active_tasks[task_id] = {
        "folder_path": folder_path,
        "status": "running",
        "logs": []
    }
    
    return jsonify({"task_id": task_id})

@app.route("/api/run/<task_id>/stream")
def stream_logs(task_id):
    task = active_tasks.get(task_id)
    if not task:
        return Response("Task not found", status=404)
        
    folder_path = task["folder_path"]
    
    def generate():
        pipeline_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
        script_path = os.path.join(pipeline_dir, "run_enrichment.py")
        
        proc = subprocess.Popen(
            [sys.executable, script_path, folder_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            cwd=pipeline_dir
        )
        
        for line in proc.stdout:
            log_line = line.rstrip()
            task["logs"].append(log_line)
            yield f"data: {json.dumps({'log': log_line})}\n\n"
            
        proc.wait()
        if proc.returncode == 0:
            task["status"] = "success"
            yield f"data: {json.dumps({'done': True, 'success': True})}\n\n"
        else:
            task["status"] = "failed"
            yield f"data: {json.dumps({'done': True, 'success': False, 'exit_code': proc.returncode})}\n\n"
            
    return Response(generate(), mimetype="text/event-stream")

@app.route("/api/results")
def get_results():
    folder = request.args.get("folder")
    if not folder:
        return jsonify({"error": "folder parameter is required"}), 400
        
    enrichment_path = os.path.join(folder, "concept-manifest-enrichment.json")
    manifest_path = os.path.join(folder, "manifest.json")
    
    results = {}
    if os.path.exists(enrichment_path):
        try:
            with open(enrichment_path, "r", encoding="utf-8") as f:
                results["enrichment"] = json.load(f)
        except Exception as e:
            results["enrichment_error"] = str(e)
            
    if os.path.exists(manifest_path):
        try:
            with open(manifest_path, "r", encoding="utf-8") as f:
                results["manifest"] = json.load(f)
        except Exception as e:
            results["manifest_error"] = str(e)
            
    if not results:
        return jsonify({"error": "No results found in the specified folder"}), 404
        
    return jsonify(results)

@app.route("/api/batch")
def batch_status():
    root = request.args.get("root")
    if not root:
        return jsonify({"error": "root parameter is required"}), 400
        
    if not os.path.isdir(root):
        return jsonify({"error": f"Root path is not a directory: {root}"}), 400
        
    subfolders = []
    for item in os.listdir(root):
        path = os.path.join(root, item)
        if os.path.isdir(path):
            enrichment_exists = os.path.exists(os.path.join(path, "concept-manifest-enrichment.json"))
            manifest_exists = os.path.exists(os.path.join(path, "manifest.json"))
            subfolders.append({
                "name": item,
                "path": path,
                "status": "enriched" if enrichment_exists else "pending",
                "has_manifest": manifest_exists
            })
            
    return jsonify({"root": root, "subfolders": subfolders})

@app.route("/api/batch/run", methods=["POST"])
def batch_run():
    data = request.json or {}
    folder_paths = data.get("folder_paths", [])
    if not folder_paths:
        return jsonify({"error": "folder_paths is required"}), 400
        
    task_id = str(uuid.uuid4())
    active_tasks[task_id] = {
        "folder_paths": folder_paths,
        "status": "running",
        "current_index": 0,
        "logs": []
    }
    return jsonify({"task_id": task_id})

@app.route("/api/batch/run/<task_id>/stream")
def stream_batch_logs(task_id):
    task = active_tasks.get(task_id)
    if not task:
        return Response("Task not found", status=404)
        
    folder_paths = task["folder_paths"]
    
    def generate():
        pipeline_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
        script_path = os.path.join(pipeline_dir, "run_enrichment.py")
        
        for idx, path in enumerate(folder_paths):
            task["current_index"] = idx
            yield f"data: {json.dumps({'batch_progress': {'current': idx + 1, 'total': len(folder_paths), 'folder': os.path.basename(path)}})}\n\n"
            
            proc = subprocess.Popen(
                [sys.executable, script_path, path],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                cwd=pipeline_dir
            )
            
            for line in proc.stdout:
                log_line = line.rstrip()
                task["logs"].append(log_line)
                yield f"data: {json.dumps({'log': f'[{os.path.basename(path)}] {log_line}'})}\n\n"
                
            proc.wait()
            
        task["status"] = "success"
        yield f"data: {json.dumps({'done': True, 'success': True})}\n\n"
        
    return Response(generate(), mimetype="text/event-stream")

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5000, debug=True)
