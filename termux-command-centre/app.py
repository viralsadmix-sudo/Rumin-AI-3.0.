import os, sqlite3
from datetime import date, datetime
from pathlib import Path
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

DB=Path(__file__).with_name(os.getenv("DATABASE_PATH","command_centre.db"))
STATIC=Path(__file__).parent/"static"
app=FastAPI(title="AI Command Centre")
app.mount("/static",StaticFiles(directory=STATIC),name="static")

def conn():
    c=sqlite3.connect(DB); c.row_factory=sqlite3.Row; return c
def stamp(): return datetime.now().astimezone().isoformat(timespec="seconds")
with conn() as c:
    c.executescript("""
    CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,details TEXT DEFAULT '',priority TEXT DEFAULT 'normal',due TEXT DEFAULT '',done INTEGER DEFAULT 0,created TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,body TEXT NOT NULL,tags TEXT DEFAULT '',created TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS approvals(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,description TEXT NOT NULL,status TEXT DEFAULT 'pending',created TEXT NOT NULL);
    """)
class Task(BaseModel):
    title:str=Field(min_length=1,max_length=200)
    details:str=""
    priority:str="normal"
    due:str=""
class Note(BaseModel):
    title:str=Field(min_length=1,max_length=200)
    body:str=Field(min_length=1,max_length=12000)
    tags:str=""
class Approval(BaseModel):
    kind:str
    description:str=Field(min_length=1,max_length=1000)
@app.get("/")
def home(): return FileResponse(STATIC/"index.html")
@app.get("/api/health")
def health(): return {"ok":True,"device":"Android Termux","time":stamp()}
@app.get("/api/tasks")
def tasks():
    with conn() as c: return [dict(x) for x in c.execute("SELECT * FROM tasks ORDER BY done,id DESC")]
@app.post("/api/tasks")
def add_task(t:Task):
    with conn() as c:
        cur=c.execute("INSERT INTO tasks(title,details,priority,due,created) VALUES(?,?,?,?,?)",(t.title,t.details,t.priority,t.due,stamp()))
        return {"ok":True,"id":cur.lastrowid}
@app.post("/api/tasks/{i}/toggle")
def toggle(i:int):
    with conn() as c:
        row=c.execute("SELECT done FROM tasks WHERE id=?",(i,)).fetchone()
        if not row: raise HTTPException(404,"Task not found")
        c.execute("UPDATE tasks SET done=? WHERE id=?",(0 if row["done"] else 1,i))
    return {"ok":True}
@app.delete("/api/tasks/{i}")
def delete_task(i:int):
    with conn() as c:
        cur=c.execute("DELETE FROM tasks WHERE id=?",(i,))
        if not cur.rowcount: raise HTTPException(404,"Task not found")
    return {"ok":True}
@app.get("/api/notes")
def notes(q:str=""):
    with conn() as c:
        p=f"%{q}%"
        return [dict(x) for x in c.execute("SELECT * FROM notes WHERE title LIKE ? OR body LIKE ? OR tags LIKE ? ORDER BY id DESC",(p,p,p))]
@app.post("/api/notes")
def add_note(n:Note):
    with conn() as c:
        cur=c.execute("INSERT INTO notes(title,body,tags,created) VALUES(?,?,?,?)",(n.title,n.body,n.tags,stamp()))
        return {"ok":True,"id":cur.lastrowid}
@app.get("/api/report")
def report():
    today=date.today().isoformat()
    with conn() as c:
        total=c.execute("SELECT count(*) n FROM tasks").fetchone()["n"]
        open_n=c.execute("SELECT count(*) n FROM tasks WHERE done=0").fetchone()["n"]
        done=c.execute("SELECT count(*) n FROM tasks WHERE done=1").fetchone()["n"]
        notes_n=c.execute("SELECT count(*) n FROM notes WHERE substr(created,1,10)=?",(today,)).fetchone()["n"]
        pending=c.execute("SELECT count(*) n FROM approvals WHERE status='pending'").fetchone()["n"]
    return {"date":today,"total":total,"open":open_n,"done":done,"notes_today":notes_n,"pending":pending}
@app.get("/api/approvals")
def approvals():
    with conn() as c: return [dict(x) for x in c.execute("SELECT * FROM approvals ORDER BY id DESC")]
@app.post("/api/approvals")
def add_approval(a:Approval):
    with conn() as c:
        cur=c.execute("INSERT INTO approvals(kind,description,created) VALUES(?,?,?)",(a.kind,a.description,stamp()))
        return {"ok":True,"id":cur.lastrowid,"note":"Request saved only; no external action executed."}
@app.post("/api/approvals/{i}/{decision}")
def decide(i:int,decision:str):
    if decision not in ("approved","rejected"): raise HTTPException(400,"Invalid decision")
    with conn() as c:
        cur=c.execute("UPDATE approvals SET status=? WHERE id=?",(decision,i))
        if not cur.rowcount: raise HTTPException(404,"Request not found")
    return {"ok":True,"note":"Decision recorded only; no external action executed."}
