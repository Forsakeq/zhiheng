import json,re,hashlib
from pathlib import Path

BASE=Path('C:/Users/86136/.codex/visualizations/2026/10/10/01a1238a-cbb3-70b3-88f9-97a9695f08f3/health-backend-plan-20261016')
OUT=Path('E:/new项目/database/_work/check_constraints.json')
catalog=json.loads((BASE/'_work/database-schema-catalog.json').read_text(encoding='utf-8-sig'))
tables={t['name']:t for t in catalog}
checks=[];names=set();expressions=set()
def q(c): return '`'+c+'`'
def lit(s): return "'"+s.replace("'","''")+"'"
def add(t,suffix,expression,note):
    name='ck_'+t+'_'+suffix
    if len(name)>64: name=name[:53]+'_'+hashlib.sha256(name.encode()).hexdigest()[:10]
    if (t,expression) in expressions:return
    assert name not in names,name
    names.add(name);expressions.add((t,expression))
    checks.append(dict(table=t,name=name,expression=expression,note=note))
def enum(t,c,values):
    add(t,c+'_enum','CAST('+q(c)+' AS BINARY) IN ('+','.join(lit(v) for v in values)+')','明确枚举；CAST AS BINARY使大小写精确，NULL可选列仍允许SQL NULL。')
def rng(t,c,lo,hi):
    add(t,c+'_range',f'{q(c)} BETWEEN {lo} AND {hi}','文档或已明确接口范围；NULL可选值保留SQL UNKNOWN语义。')
def pair(t,a,b):
    add(t,a+'_pair',f'({q(a)} IS NULL) = ({q(b)} IS NULL)','文档规定的同空同有。')
def obj(t,c,types=('OBJECT',)):
    add(t,c+'_shape','JSON_TYPE('+q(c)+') IN ('+','.join(lit(x) for x in types)+')','仅约束已明确JSON形状；业务字段/schema与引用所有权仍由服务校验。')
def jschema(t,c,schema,suffix='schema'):
    raw=json.dumps(schema,ensure_ascii=False,separators=(',',':'))
    add(t,c+'_'+suffix,'JSON_SCHEMA_VALID('+lit(raw)+','+q(c)+') = 1','MySQL JSON_SCHEMA_VALID draft-04单表结构约束；NULL可选列仍允许SQL NULL。')

open_codes={('auth_session','revoke_reason'),('goal_definition','code'),('task_event','reason_code'),('outbox_event','event_type'),('file_object','storage_provider'),('audit_log','action_code'),('sync_operation','operation_code'),('device_task_receipt','rejection_code'),('sys_role','role_code')}
enum_overrides={
 ('auth_verification','channel'):['EMAIL'],
 ('plan_version_task','task_type'):['ROUTINE','ACTIVITY','MEAL_HABIT','RECORDING','CUSTOM'],
 ('plan_rule','task_type'):['ROUTINE','ACTIVITY','MEAL_HABIT','RECORDING','CUSTOM'],
 ('sys_permission','resource_group'):['KNOWLEDGE','CONFIG','AUDIT','FEEDBACK','ROLE','USER','JOB'],
 ('record_tag','applicable_record_type'):['SLEEP','ACTIVITY','MEAL','ENERGY'],
 ('notification_inbox','target_type'):['PLAN','TASK','PRIVACY_REQUEST','REVIEW'],
 ('sync_change','entity_type'):['PROFILE','PREFERENCE','GOAL','AVAILABILITY_RULE','AVAILABILITY_EXCEPTION','RECORD','RECORD_TAG','PLAN','PLAN_VERSION','TASK','REMINDER_RULE','NOTIFICATION'],
}
enum_columns={}
for t in catalog:
    tn=t['name']; fdict={f[0]:f for f in t['fields']}
    if t['common'].startswith('CM'):add(tn,'row_version_positive','`row_version` >= 1','CM乐观版本从1开始。')
    for c,typ,req,desc in t['fields']:
        override=enum_overrides.get((tn,c))
        m=re.match(r'^([A-Z][A-Z0-9_]*(?:\s*/\s*[A-Z][A-Z0-9_]*)+)',desc)
        vals=override or (re.split(r'\s*/\s*',m.group(1)) if m and (tn,c) not in open_codes else None)
        if vals and ('VARCHAR' in typ or 'CHAR' in typ):
            enum(tn,c,vals);enum_columns[(tn,c)]=vals
        if c=='detail_type' and tn.endswith('_record_detail'):
            enum(tn,c,[tn.split('_record_detail')[0].upper()])
        if typ.startswith('TINYINT') and (c.startswith('is_') or c in {'reduced_motion','quiet_hours_enabled','vegetable_present','fruit_present','protein_food_present','whole_grain_present','respect_quiet_hours','end_day_offset'}):
            rng(tn,c,0,1)
        if typ=='TIME':
            add(tn,c+'_time',f'{q(c)} >= \'00:00:00\' AND {q(c)} < \'24:00:00\' AND MOD(TIME_TO_SEC({q(c)}),60)=0','本地时分精度；拒绝负TIME、24小时以上及非整分钟。')
        if 'INT' in typ and c!='before_row_version' and (c.endswith('_version') or c.endswith('_epoch') or c.endswith('_revision') or c.endswith('_revision_no') or c in {'version_no','revision_no','schema_version','generation','citation_no','reference_no'}):
            add(tn,c+'_positive',q(c)+' >= 1','文档中从1起的版本/代际/引用序号；SQL NULL仍允许。')
    ciphers=[c for c in fdict if c.endswith('_cipher')]
    if 'encryption_key_ref' in fdict and ciphers:
        for c in ciphers:
            add(tn,c+'_key',f'{q(c)} IS NULL OR `encryption_key_ref` IS NOT NULL','非空密文必须有外部密钥引用；不把密钥本体存库。')
        if tn not in {'file_object','import_batch'} and not fdict['encryption_key_ref'][2].startswith('NN'):
            add(tn,'key_has_cipher','`encryption_key_ref` IS NULL OR ('+' OR '.join(q(c)+' IS NOT NULL' for c in ciphers)+')','该表key_ref只用于列明密文，避免孤立密钥引用。')

# 未在字段自然语言中展开、但在正式API完整冻结的同步实体白名单。
enum('sync_change','entity_type',enum_overrides[('sync_change','entity_type')])

# 明确数值范围与专用版本/时钟
for t,c,lo,hi in [
 ('user_profile','week_start_day',1,7),('user_preference','daily_task_limit',1,12),('user_preference','default_task_minutes',1,240),('user_preference','preferred_session_minutes',1,240),('user_preference','font_scale','0.80','2.00'),
 ('auth_verification','max_attempts',1,10),('user_goal','priority',1,5),('availability_rule','weekday',1,7),('availability_rule','start_minute',0,1439),('availability_rule','end_minute',0,1439),('health_record','utc_offset_minutes',-840,840),
 ('sleep_record_detail','elapsed_minutes',1,2160),('sleep_record_detail','rested_level',1,5),('activity_record_detail','actual_duration_minutes',1,1440),('activity_record_detail','distance_meters',0,1000000),('meal_record_detail','satiety_level',1,5),('energy_record_detail','energy_level',1,5),
 ('task','planned_duration_minutes',1,1440),('task','priority',1,5),('plan_version_task','planned_duration_minutes',1,1440),('plan_version_task','priority',1,5),('plan_rule','planned_duration_minutes',1,1440),('reminder_rule','offset_minutes',-1440,0)]:rng(t,c,lo,hi)
add('user_sync_clock','retention','`retained_from_seq` >= 1 AND `retained_from_seq` <= `committed_seq` + 1','无事件时retained_from_seq=committed_seq+1；不是自增ID游标。')
add('sync_change','change_seq_positive','`change_seq` >= 1','真实变更序号从1起。')
add('sync_operation','last_change_seq_positive','`last_change_seq` IS NULL OR `last_change_seq` >= 1','不进入个人同步流的回执可无seq。')

# 时间、来源、配对及状态
add('user_preference','quiet_hours','(`quiet_start` IS NULL) = (`quiet_end` IS NULL) AND (`quiet_hours_enabled`=0 OR (`quiet_start` IS NOT NULL AND `quiet_end` IS NOT NULL AND `quiet_start`<>`quiet_end`))','免打扰起止成对，启用时不可缺失或相等；可跨午夜。')
add('auth_session','expiry','`expires_at` > `issued_at` AND `family_expires_at` >= `expires_at`','旋转不能超过绝对会话截止。')
pair('auth_session','consumed_at','replaced_by_session_id')
add('auth_verification','attempts','`attempt_count` <= `max_attempts`','挑战失败次数上限。')
add('auth_verification','expiry','`expires_at` > `created_at`','验证码失效时间晚于创建。')
pair('auth_verification','destination_cipher','encryption_key_ref')
pair('client_installation','pairing_token_hash','pairing_expires_at')
pair('client_installation','device_credential_hash','credential_expires_at')
add('client_installation','parent_kind',"(CAST(`installation_kind` AS BINARY)='WATCH' AND `parent_installation_id` IS NOT NULL) OR (CAST(`installation_kind` AS BINARY) IN ('PHONE','WEB') AND `parent_installation_id` IS NULL)",'WATCH必须有PHONE父安装；同用户/父类型由FK及服务保证。')
add('client_installation','web_platform',"(CAST(`installation_kind` AS BINARY)='WEB') = (CAST(`platform` AS BINARY)='WEB')",'WEB安装与WEB平台对应；PHONE/WATCH不冒用WEB。')
add('client_installation','ack_bound','`last_ack_seq` <= `last_delivered_seq`','ack不得超出已交付水位。')
add('client_installation','initializing_lease',"CAST(`sync_state` AS BINARY)<>'INITIALIZING' OR (`bootstrap_id` IS NOT NULL AND `bootstrap_from_seq` IS NOT NULL AND `bootstrap_expires_at` IS NOT NULL AND `bootstrap_epoch` IS NOT NULL)",'初始化必须有完整租约；READY不继续受2小时租约限制。')
add('client_installation','ready_epoch',"CAST(`sync_state` AS BINARY)<>'READY' OR (`sync_epoch` IS NOT NULL AND `bootstrap_completed_at` IS NOT NULL)",'READY需要已完成初始化的epoch与时间。')
for t,a,b in [('user_goal','start_local_date','end_local_date'),('availability_rule','valid_from','valid_until'),('plan','range_start_date','range_end_date'),('plan_rule','valid_from','valid_until'),('reminder_rule','valid_from','valid_until'),('user_role_assignment','valid_from','valid_until')]:
    add(t,'valid_date_range',f'{q(b)} IS NULL OR {q(b)} >= {q(a)}','日期/有效期不反向；NULL表示无截止。')
add('availability_rule','duration','`end_minute` + 1440*`end_day_offset` - `start_minute` BETWEEN 1 AND 1440','跨午夜显式end_day_offset，有效时段最多一天。')
add('health_record','time_range','`ended_at` IS NULL OR `ended_at` >= `started_at`','记录区间不反向。')
add('health_record','type_time',"(CAST(`record_type` AS BINARY) IN ('SLEEP','ACTIVITY') AND `ended_at` IS NOT NULL AND `ended_at`>`started_at`) OR (CAST(`record_type` AS BINARY)='ENERGY' AND `ended_at` IS NULL) OR CAST(`record_type` AS BINARY)='MEAL'",'睡眠/活动必须有结束；主观精力是单点，MEAL保留可选结束。')
add('health_record','source_pair',"(CAST(`source_type` AS BINARY)='EXTERNAL' AND `source_connection_id` IS NOT NULL AND `provider_record_key` IS NOT NULL) OR (CAST(`source_type` AS BINARY) IN ('MANUAL','AI_CONFIRMED') AND `source_connection_id` IS NULL AND `provider_record_key` IS NULL)",'文档明确三种来源互斥；外部稳定键必须齐全。')
add('sleep_record_detail','sleep_range','`wake_at` > `sleep_start_at` AND TIMESTAMPDIFF(SECOND,`sleep_start_at`,`wake_at`) <= 129600','用户记录睡眠区间大于0、至多36小时；非实测总睡眠。')
add('activity_record_detail','other_name',"CAST(`activity_type` AS BINARY)<>'OTHER' OR (`custom_activity_name` IS NOT NULL AND CHAR_LENGTH(TRIM(`custom_activity_name`))>=1)",'OTHER活动必须有自定义名称。')
pair('activity_record_detail','distance_meters','distance_method')
for t in ['task','plan_version_task']:
    add(t,'schedule','`scheduled_end_at` > `scheduled_start_at` AND `planned_duration_minutes`*60 <= TIMESTAMPDIFF(SECOND,`scheduled_start_at`,`scheduled_end_at`)','计划时长不超过明确时间窗，不等同实际活动。')
    pair(t,'rule_id','occurrence_key')
add('plan_version','sealed_metadata',"CAST(`status` AS BINARY)<>'SEALED' OR (`sealed_at` IS NOT NULL AND `sealed_by_operation_id` IS NOT NULL AND `content_digest` IS NOT NULL)",'封存必须有审计与摘要；子项不可变及task_id非空须服务事务保证。')
add('plan_rule','local_window','(TIME_TO_SEC(`end_local_time`)+86400*`end_day_offset`-TIME_TO_SEC(`start_local_time`)) BETWEEN 60 AND 86400 AND `planned_duration_minutes`*60 <= TIME_TO_SEC(`end_local_time`)+86400*`end_day_offset`-TIME_TO_SEC(`start_local_time`)','模板时间窗1–1440分钟，计划时长不能超过窗。')
add('plan_rule','weekly_days',"CAST(`recurrence_kind` AS BINARY)<>'WEEKLY' OR `weekdays_json` IS NOT NULL",'WEEKLY必须有星期数组；DAILY可无。')
add('task_event','row_versions','`after_row_version` > `before_row_version`','CREATE可before=0；动作后版本必须增加。')
add('ai_draft','revision_order','`validated_revision_no` IS NULL OR `validated_revision_no` <= `current_revision_no`','通过校验的修订不能来自未来。')
add('ai_draft','applied_receipt',"(CAST(`status` AS BINARY)='APPLIED' AND `applied_at` IS NOT NULL AND `applied_resource_type` IS NOT NULL AND `applied_resource_id` IS NOT NULL AND `applied_operation_id` IS NOT NULL) OR (CAST(`status` AS BINARY)<>'APPLIED' AND `applied_at` IS NULL AND `applied_resource_type` IS NULL AND `applied_resource_id` IS NULL AND `applied_operation_id` IS NULL)",'文档给出的APPLIED四字段完整回执约束；非APPLIED不能残留。')
add('ai_draft_revision','source_revision','`source_revision` IS NULL OR `source_revision` < `revision_no`','每次修订基于更早版本。')
add('knowledge_article_version','published_time',"CAST(`status` AS BINARY)<>'PUBLISHED' OR `published_at` IS NOT NULL",'已发布版本必须有发布时间。')
add('knowledge_article_version','different_reviewer','`reviewer_user_id` IS NULL OR `author_user_id` IS NULL OR `reviewer_user_id`<>`author_user_id`','最终接口规定编辑审核分离，不能自审。')
add('daily_stat','task_counts','`task_completed_count` + `task_skipped_count` <= `task_denominator`','完成和跳过均在执行分母内。')
add('daily_stat','energy_sum','(`energy_sample_count`=0 AND `energy_level_sum` IS NULL) OR (`energy_sample_count`>0 AND `energy_level_sum` IS NOT NULL AND `energy_level_sum` BETWEEN `energy_sample_count` AND 5*`energy_sample_count`)','无主观精力数据用NULL；有数据时每次五级评分。')
add('weekly_review','week_range','`week_end_date` = DATE_ADD(`week_start_date`, INTERVAL 6 DAY)','周回顾固定7天。')
add('weekly_review_evidence','exactly_one',"(CAST(`evidence_type` AS BINARY)='DAILY_STAT' AND `daily_stat_id` IS NOT NULL AND `record_id` IS NULL AND `task_event_id` IS NULL) OR (CAST(`evidence_type` AS BINARY)='RECORD' AND `record_id` IS NOT NULL AND `daily_stat_id` IS NULL AND `task_event_id` IS NULL) OR (CAST(`evidence_type` AS BINARY)='TASK_EVENT' AND `task_event_id` IS NOT NULL AND `daily_stat_id` IS NULL AND `record_id` IS NULL)",'每条证据恰好指向一种实体，显式IS NOT NULL消除UNKNOWN漏洞。')
add('reminder_rule','trigger_fields',"(CAST(`trigger_kind` AS BINARY)='OFFSET' AND CAST(`rule_type` AS BINARY)='TASK_START' AND `task_id` IS NOT NULL AND `offset_minutes` IS NOT NULL AND `local_fire_time` IS NULL) OR (CAST(`trigger_kind` AS BINARY)='DAILY_LOCAL' AND CAST(`rule_type` AS BINARY)='RECORD_CHECKIN' AND `task_id` IS NULL AND `local_fire_time` IS NOT NULL AND `offset_minutes` IS NULL)",'任务offset与每日本地提醒两种明确分支。')
add('reminder_delivery','task_version_pair','(`task_id` IS NULL) = (`task_row_version` IS NULL)','有任务提醒必须附任务版本；日常记录提醒两者皆空。')
add('file_object','clean_metadata',"CAST(`status` AS BINARY)<>'CLEAN' OR (`byte_size` IS NOT NULL AND `sha256` IS NOT NULL AND `mime_type` IS NOT NULL AND `completed_at` IS NOT NULL)",'文件CLEAN必须已核验实际元数据并有完成时间。')
add('file_object','expected_size','`expected_byte_size` >= 1','上传/内部文件的预期大小必须为正。')
add('file_object','actual_size','`byte_size` IS NULL OR `byte_size` >= 1','可选实际字节数非零。')
add('notification_inbox','target_pair','(`target_type` IS NULL) = (`target_id` IS NULL)','目标类型和ID成对；无目标用SQL NULL。')
add('outbox_event','claim_lease',"CAST(`status` AS BINARY)<>'CLAIMED' OR (`lease_owner` IS NOT NULL AND `lease_until` IS NOT NULL)",'已领取事件必须有工作者与租约；外部调用不持行锁。')
add('import_batch','counts','`total_count` IS NULL OR `imported_count` + `duplicate_count` + `rejected_count` <= `total_count`','导入成功、重复、拒绝数量不超过已知候选总数。')
add('import_batch','source_kind',"(CAST(`kind` AS BINARY)='EXTERNAL_RECORDS' AND `source_connection_id` IS NOT NULL AND `authorization_revision` IS NOT NULL AND `source_file_id` IS NULL) OR (CAST(`kind` AS BINARY) IN ('COURSE_IMAGE','VOICE_RECORD') AND `source_file_id` IS NOT NULL AND `source_connection_id` IS NULL)",'外部读取与图片/语音导入的来源引用分开。')

# JSON形状及有明确边界的数组
array_shapes={
 'user_preference':{'preferred_activity_types_json','excluded_activity_types_json'},
 'availability_exception':{'slots_json'},'client_installation':{'device_scope_json'},
 'plan_version':{'affected_task_ids_json'},'plan_rule':{'weekdays_json'},
 'knowledge_article':{'tag_codes_json'},'knowledge_article_version':{'tags_json'},
 'daily_stat':{'quality_flags_json','source_time_zones_json'},'feedback_ticket':{'attachment_file_ids_json'},
 'external_data_connection':{'scopes_json'},
}
flex_shapes={('user_consent','scope_json'),('user_consent_event','scope_json'),('import_batch','error_summary_json')}
for t in catalog:
    for c,typ,req,desc in t['fields']:
        if typ!='JSON':continue
        if t['name']=='app_configuration' and c=='value_json':continue
        types=('ARRAY',) if c in array_shapes.get(t['name'],set()) else (('OBJECT','ARRAY') if (t['name'],c) in flex_shapes else ('OBJECT',))
        obj(t['name'],c,types)
activity=['WALK','RUN','CYCLING','STRETCH','SPORT','OTHER']
for c in ['preferred_activity_types_json','excluded_activity_types_json']:
    jschema('user_preference',c,{'type':'array','maxItems':6,'uniqueItems':True,'items':{'type':'string','enum':activity}})
jschema('plan_rule','weekdays_json',{'type':'array','minItems':1,'maxItems':7,'uniqueItems':True,'items':{'type':'integer','minimum':1,'maximum':7}})
ids={'type':'string','pattern':'^[1-9][0-9]{0,19}$'}
jschema('plan_version','affected_task_ids_json',{'type':'array','maxItems':500,'uniqueItems':True,'items':ids})
jschema('feedback_ticket','attachment_file_ids_json',{'type':'array','maxItems':3,'uniqueItems':True,'items':ids})
for t,c in [('knowledge_article','tag_codes_json'),('knowledge_article_version','tags_json')]:
    jschema(t,c,{'type':'array','maxItems':10,'uniqueItems':True,'items':{'type':'string','minLength':1,'maxLength':30}})
jschema('client_installation','device_scope_json',{'type':'array','maxItems':2,'uniqueItems':True,'items':{'type':'string','enum':['task.read','task.action']}})
jschema('daily_stat','source_time_zones_json',{'type':'array','uniqueItems':True,'items':{'type':'string','minLength':1,'maxLength':64}})
# slots中跨午夜与DST语义必须服务校验；结构和明确的最大数量可由引擎约束。
slot={'type':'object','required':['kind','startTime','endTime','endDayOffset'],'properties':{'slotKey':{'type':'string','minLength':1},'kind':{'type':'string','enum':['BUSY','AVAILABLE','REST']},'startTime':{'type':'string','pattern':'^([01][0-9]|2[0-3]):[0-5][0-9]$'},'endTime':{'type':'string','pattern':'^([01][0-9]|2[0-3]):[0-5][0-9]$'},'endDayOffset':{'type':'integer','minimum':0,'maximum':1},'startOffsetMinutes':{'type':['integer','null'],'minimum':-840,'maximum':840},'endOffsetMinutes':{'type':['integer','null'],'minimum':-840,'maximum':840}}}
jschema('availability_exception','slots_json',{'type':'array','maxItems':24,'items':slot})
envelope={'type':'object','required':['alg','keyRef','nonce','ciphertext'],'properties':{k:{'type':'string','minLength':1} for k in ['alg','keyRef','nonce','ciphertext']}}
for t,c in [('ai_draft_revision','payload_json'),('weekly_review','suggestions_json'),('import_batch','candidate_payload_json')]:jschema(t,c,envelope,'encrypted_envelope')
obj('app_configuration','value_json')
config_schema={'BOOLEAN':{'booleanValue':{'type':'boolean'}},'NUMBER':{'numberValue':{'type':'number'}},'STRING':{'stringValue':{'type':'string','minLength':1,'maxLength':2000}}}
# OBJECT值是API的FeatureRuleValue或StringListValue本体，不添加objectValue层。
branches=[]
for typ,props in config_schema.items():
    ss={'type':'object','required':list(props),'properties':props,'additionalProperties':False}
    raw=json.dumps(ss,separators=(',',':'))
    branches.append('(CAST(`value_type` AS BINARY)='+lit(typ)+' AND JSON_SCHEMA_VALID('+lit(raw)+',`value_json`)=1)')
feature={'type':'object','required':['featureCode','enabled','allowedPlatforms'],'properties':{'featureCode':{'type':'string','minLength':1,'maxLength':64},'enabled':{'type':'boolean'},'allowedPlatforms':{'type':'array','maxItems':4,'uniqueItems':True,'items':{'type':'string','enum':['ANDROID','HARMONYOS','IOS','WEB']}},'minAppVersion':{'type':['string','null'],'maxLength':32},'rolloutPercent':{'type':'integer','minimum':0,'maximum':100}},'additionalProperties':False}
stringlist={'type':'object','required':['items'],'properties':{'items':{'type':'array','maxItems':100,'uniqueItems':True,'items':{'type':'string','minLength':1,'maxLength':100}}},'additionalProperties':False}
object_schema=json.dumps({'anyOf':[feature,stringlist]},separators=(',',':'))
branches.append("(CAST(`value_type` AS BINARY)='OBJECT' AND JSON_SCHEMA_VALID("+lit(object_schema)+",`value_json`)=1)")
add('app_configuration','value_wrapper',' OR '.join(branches),'value_type是API包装分支；不误把包装OBJECT要求为顶层BOOLEAN/NUMBER。')

# 验证表达式中字段名存在（不可能跨表）、名字唯一、字符长度，以及枚举能装入字段。
for row in checks:
    tc=tables[row['table']]
    cols={f[0] for f in tc['fields']}
    if tc['common'].startswith('CM'):cols.update(['id','created_at','updated_at','row_version','deleted_at'])
    elif tc['common'].startswith('CA'):cols.update(['id','created_at'])
    elif tc['common'].startswith('CD'):cols.update(['record_id','created_at','updated_at'])
    for name in re.findall(r'`([^`]+)`',row['expression']):assert name in cols,(row['name'],name)
    assert not re.search(r'\b(SELECT|NOW|CURRENT_TIMESTAMP)\b',row['expression'],re.I)
OUT.parent.mkdir(parents=True,exist_ok=True)
OUT.write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
warnings=[]
for (tn,c),vs in enum_columns.items():
    typ=next(f[1] for f in tables[tn]['fields'] if f[0]==c)
    m=re.search(r'(?:VAR)?CHAR\((\d+)\)',typ)
    if m and max(map(len,vs))>int(m.group(1)):warnings.append((tn,c,typ,max(vs,key=len)))
print(json.dumps({'checks':len(checks),'tables':len({r['table'] for r in checks}),'output':str(OUT),'max_name_length':max(map(len,names)),'enum_width_warnings':warnings},ensure_ascii=False))
