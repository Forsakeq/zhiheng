"""Meaningful integrity checks in the disposable validation schema; all fixtures roll back."""
import hashlib
import json
import sys
import uuid
from pathlib import Path
from mysql_wire import MySQL, MySQLError

sys.stdout.reconfigure(encoding='utf-8')
BASE = Path(__file__).resolve().parent
MODEL = json.loads((BASE/'schema_model.json').read_text('utf-8-sig'))
conn = MySQL((BASE/'validation-port.txt').read_text().strip())
schema = sys.argv[1] if len(sys.argv)>1 else 'zhiheng_validation_01'
assert schema.startswith('zhiheng_validation_')
conn.query('USE `' + schema + '`')
checks = []
NOW = '2026-10-16 12:00:00.000'


def lit(value):
    if value is None:
        return 'NULL'
    if isinstance(value, bytes):
        return "X'" + value.hex() + "'"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, (dict, list)):
        value = json.dumps(value, ensure_ascii=False, separators=(',',':'))
    return "'" + str(value).replace('\\','\\\\').replace("'","''") + "'"


def insert(table, values):
    values = dict(values)
    fields = {c['name'] for c in MODEL[table]['columns']}
    for col in ('created_at','updated_at'):
        if col in fields:
            values.setdefault(col, NOW)
    sql = 'INSERT INTO `' + table + '` (' + ','.join('`'+k+'`' for k in values) + ') VALUES ('
    sql += ','.join(lit(v) for v in values.values()) + ')'
    return conn.query(sql)['insertId']


def expect_error(name, sql, expected):
    conn.query('SAVEPOINT integrity_case')
    try:
        conn.query(sql)
        checks.append({'name':name,'passed':False,'error':'Unexpected success'})
    except MySQLError as exc:
        checks.append({'name':name,'passed':exc.code in expected,'mysqlCode':exc.code,'expected':expected})
    finally:
        conn.query('ROLLBACK TO SAVEPOINT integrity_case')
        conn.query('RELEASE SAVEPOINT integrity_case')


def app_user(label):
    return insert('app_user', {
        'account_no':('FIXTURE'+uuid.uuid4().hex)[:26],
        'registered_at':NOW,
        'email_lookup_hmac':hashlib.sha256(label.encode()).digest(),
        'email_cipher':b'disposable encrypted fixture placeholder',
        'email_key_ref':'test-only-key', 'email_verified_at':NOW,
        'registration_policy_version':'test-only-v1',
        'registration_policy_accepted_at':NOW,
    })


def health_record(user, key=None):
    return insert('health_record', {
        'user_id':user, 'client_record_id':key or str(uuid.uuid4()), 'record_type':'SLEEP',
        'started_at':'2026-10-15 23:00:00.000','ended_at':'2026-10-16 07:00:00.000',
        'record_local_date':'2026-10-16','record_time_zone':'UTC','utc_offset_minutes':0,
        'confirmed_at':'2026-10-16 07:00:00.000',
    })


def plan(user):
    return insert('plan', {
        'user_id':user,'plan_key':str(uuid.uuid4()),'name':'Disposable plan fixture',
        'plan_kind':'DAY','range_start_date':'2026-10-16','range_end_date':'2026-10-16',
        'plan_time_zone':'UTC',
    })


def plan_version(user, plan_id):
    return insert('plan_version', {
        'user_id':user,'plan_id':plan_id,'version_no':1,'source_type':'MANUAL',
        'effective_from':'2026-10-16 00:00:00.000','affected_task_ids_json':[],
        'change_summary':'Disposable test version','constraint_snapshot_json':{},
    })


def task_values(user, plan_id):
    return {'user_id':user,'plan_id':plan_id,'title':'Disposable task','task_type':'ROUTINE',
        'scheduled_start_at':'2026-10-16 12:00:00.000','scheduled_end_at':'2026-10-16 12:15:00.000',
        'task_local_date':'2026-10-16','task_time_zone':'UTC','planned_duration_minutes':15,
        'status':'DRAFT'}


report = {'serverVersion':conn.server_version,'schema':schema,'checks':checks}
try:
    counts = conn.query("SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()),"
        "(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()),"
        "(SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()),"
        "(SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK')")['rows'][0]
    report['objectCounts'] = dict(zip(['tables','columns','foreignKeys','checkConstraints'],map(int,counts)))
    expected_checks = len(json.loads((BASE/'check_constraints.json').read_text('utf-8-sig')))
    checks.append({'name':'Exact schema coverage','passed':list(map(int,counts))==[60,1066,135,expected_checks]})
    seeds = conn.query('SELECT (SELECT COUNT(*) FROM goal_definition),(SELECT COUNT(*) FROM sys_permission),'
        '(SELECT COUNT(*) FROM sys_role),(SELECT COUNT(*) FROM role_permission),(SELECT COUNT(*) FROM app_configuration),'
        '(SELECT COUNT(*) FROM app_user),(SELECT COUNT(*) FROM health_record)')['rows'][0]
    report['seedCounts'] = list(map(int,seeds))
    checks.append({'name':'Seed counts and no default user/health facts','passed':list(map(int,seeds))==[5,15,6,18,123,0,0]})
    valid = conn.query('SELECT COUNT(*) FROM app_configuration WHERE JSON_SCHEMA_VALID(schema_json,value_json)=0')['rows'][0][0]
    checks.append({'name':'All seeded configuration values satisfy their JSON schemas','passed':valid=='0'})
    conn.query('START TRANSACTION')
    user_a,user_b=app_user('fixture-a'),app_user('fixture-b')
    checks.append({'name':'Valid encrypted-account placeholder fixtures accepted','passed':bool(user_a and user_b)})
    expect_error('Unknown status rejected',f"UPDATE app_user SET status='NO_SUCH_STATE' WHERE id={user_a}",[3819])
    expect_error('Lowercase enum rejected despite default text collation',f"UPDATE app_user SET status='active' WHERE id={user_a}",[3819])
    expect_error('Zero row version rejected',f'UPDATE app_user SET row_version=0 WHERE id={user_a}',[3819])
    expect_error('Required email hash cannot be NULL',f'UPDATE app_user SET email_lookup_hmac=NULL WHERE id={user_a}',[1048])
    record_key=str(uuid.uuid4())
    record_a=health_record(user_a,record_key)
    record_b=health_record(user_b)
    try:
        health_record(user_a,record_key)
        checks.append({'name':'Offline client record UUID deduplicates','passed':False})
    except MySQLError as exc:
        checks.append({'name':'Offline client record UUID deduplicates','passed':exc.code==1062,'mysqlCode':exc.code})
    expect_error('Sleep interval cannot reverse',f"UPDATE health_record SET ended_at='2026-10-15 22:00:00' WHERE id={record_a}",[3819])
    expect_error('External record requires connection and provider key',f"UPDATE health_record SET source_type='EXTERNAL' WHERE id={record_a}",[3819])
    detail={'record_id':record_a,'user_id':user_a,'sleep_start_at':'2026-10-15 23:00:00',
        'wake_at':'2026-10-16 07:00:00','elapsed_minutes':480}
    insert('sleep_record_detail',detail)
    checks.append({'name':'Matching record detail accepted','passed':True})
    expect_error('Detail cannot cross account ownership',f'UPDATE sleep_record_detail SET user_id={user_b} WHERE record_id={record_a}',[1452])
    expect_error('Parent type cannot contradict existing detail',f"UPDATE health_record SET record_type='ACTIVITY' WHERE id={record_a}",[1451])
    plan_a,plan_b=plan(user_a),plan(user_a)
    version_a,version_b=plan_version(user_a,plan_a),plan_version(user_a,plan_b)
    expect_error('Active version cannot point to a different plan',f'UPDATE plan SET active_version_id={version_b} WHERE id={plan_a}',[1452])
    task_a=insert('task',task_values(user_a,plan_a))
    task_b=insert('task',task_values(user_a,plan_b))
    snapshot=task_values(user_a,plan_a)
    snapshot.pop('status')
    snapshot.update({'version_id':version_a,'task_id':task_a,'draft_task_key':str(uuid.uuid4()),
        'reminder_snapshot_json':{},'change_action':'ADD'})
    snapshot_id=insert('plan_version_task',snapshot)
    checks.append({'name':'Same-plan task snapshot accepted','passed':bool(snapshot_id)})
    expect_error('Snapshot cannot reference another plan task',f'UPDATE plan_version_task SET task_id={task_b} WHERE id={snapshot_id}',[1452])
    event_id=insert('task_event',{'user_id':user_a,'task_id':task_a,'event_type':'CREATE',
        'operation_id':str(uuid.uuid4()),'source_type':'MOBILE','before_row_version':0,
        'after_row_version':1,'occurred_at':NOW,'old_status':None,'new_status':'DRAFT',
        'plan_version_id':version_a,'plan_version_task_id':snapshot_id})
    checks.append({'name':'CREATE event legitimately allows before version zero','passed':bool(event_id)})
    file_id=insert('file_object',{'user_id':user_a,'file_key':str(uuid.uuid4()),'purpose':'AVATAR',
        'storage_provider':'LOCAL_PRIVATE','bucket_code':'test-only','object_key':str(uuid.uuid4()),
        'encryption_key_ref':'test-only','expected_byte_size':10,
        'expected_sha256':hashlib.sha256(b'fixture').digest(),'declared_mime_type':'image/png'})
    expect_error('Unverified upload cannot become CLEAN',f"UPDATE file_object SET status='CLEAN' WHERE id={file_id}",[3819])
    conn_a=insert('external_data_connection',{'user_id':user_a,'provider_code':'HUAWEI_HEALTH',
        'connection_key':str(uuid.uuid4()),'scopes_json':[],'consent_version':1,'status':'ACTIVE'})
    conn_b=insert('external_data_connection',{'user_id':user_a,'provider_code':'HUAWEI_HEALTH',
        'connection_key':str(uuid.uuid4()),'scopes_json':[],'consent_version':1,'status':'AUTHORIZING'})
    expect_error('Only one active connection per provider',f"UPDATE external_data_connection SET status='ACTIVE' WHERE id={conn_b}",[1062])
    conn.query(f"UPDATE external_data_connection SET status='REVOKED' WHERE id={conn_a}")
    conn.query(f"UPDATE external_data_connection SET status='ACTIVE' WHERE id={conn_b}")
    checks.append({'name':'Revoked connection releases generated active uniqueness slot','passed':True})
    feedback=insert('feedback_ticket',{'user_id':user_a,'ticket_no':('FIXTURE'+uuid.uuid4().hex)[:26],
        'category':'BUG','subject':'Disposable fixture','description_cipher':b'fixture',
        'encryption_key_ref':'test-only','attachment_file_ids_json':[]})
    expect_error('Feedback attachment count is bounded to three',
        f"UPDATE feedback_ticket SET attachment_file_ids_json=JSON_ARRAY('1','2','3','4') WHERE id={feedback}",[3819])
    change_id=insert('sync_change',{'user_id':user_a,'change_seq':1,'dataset_epoch':1,
        'entity_type':'TASK','entity_id':task_a,'entity_row_version':1,'operation':'UPSERT',
        'changed_at':NOW,'payload_json':{},'operation_id':str(uuid.uuid4())})
    expect_error('Synchronization entity type is restricted to the formal whitelist',
        f"UPDATE sync_change SET entity_type='UNREGISTERED_ENTITY' WHERE id={change_id}",[3819])
    conn.query('ROLLBACK')
except Exception as exc:
    report['setupError']=str(exc)
    try: conn.query('ROLLBACK')
    except Exception: pass
finally:
    after=conn.query('SELECT (SELECT COUNT(*) FROM app_user),(SELECT COUNT(*) FROM health_record)')['rows'][0]
    report['afterRollback']=list(map(int,after))
    checks.append({'name':'No fixture users or health records persisted','passed':after==['0','0']})
    report['passed']=sum(c['passed'] for c in checks)
    report['failed']=sum(not c['passed'] for c in checks)
    (BASE/'validation-integrity.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    conn.close()
    if report.get('setupError') or report['failed']:
        raise SystemExit(1)
