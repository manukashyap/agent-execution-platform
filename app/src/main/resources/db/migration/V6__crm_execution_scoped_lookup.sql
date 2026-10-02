-- V6: crm.upsert is reconciled by an execution-scoped lookup and reports whether it created the record.
--
-- Before: LOOKUP on external_ref alone, so a contact that predated the run counted as "our" effect, and the
-- crm.delete inverse could delete it. Now the lookup passes the ledger effect key ({{effect_key}}, the same value
-- sent as Idempotency-Key with the forward call); only a record written under that key counts as found. The
-- upsert response carries created=true|false; the compensation reconciler never deletes a record the effect only
-- updated (created=false ends that step NEEDS_ATTENTION). Also aligns crm.get's declared contract with what the
-- gateway reads: a {"contacts":[...]} envelope whose first element is the effect.

UPDATE tool_registry
   SET version = version + 1,
       output_schema = '{"type":"object","required":["external_ref","created"],
   "properties":{"contact_id":{"type":"string"},"external_ref":{"type":"string"},
                 "created":{"type":"boolean"},"effect_key":{"type":"string"}}}',
       lookup = '{"tool":"crm.get","args":{"external_ref":"{{args.external_ref}}","effect_key":"{{effect_key}}"}}',
       dry_run_example = '{"contact_id":"ct_dryrun","external_ref":"lead_dryrun","created":true}'
 WHERE tool_name = 'crm.upsert';

UPDATE tool_registry
   SET version = version + 1,
       input_schema = '{"type":"object","additionalProperties":false,"required":["external_ref"],
   "properties":{"external_ref":{"type":"string","minLength":1},"effect_key":{"type":"string","minLength":1}}}'
 WHERE tool_name = 'crm.delete';

UPDATE tool_registry
   SET version = version + 1,
       input_schema = '{"type":"object","additionalProperties":false,"required":["external_ref"],
   "properties":{"external_ref":{"type":"string","minLength":1},"effect_key":{"type":"string","minLength":1}}}',
       output_schema = '{"type":"object","required":["contacts"],"properties":{"contacts":{"type":"array",
   "items":{"type":"object","properties":{"contact_id":{"type":"string"},"external_ref":{"type":"string"},
            "name":{"type":"string"},"email":{"type":"string"},"label":{"type":"string"},
            "effect_key":{"type":"string"},"created":{"type":"boolean"}}}}}}',
       dry_run_example = '{"contacts":[{"contact_id":"ct_dryrun","external_ref":"lead_dryrun","name":"Dry Run",
   "email":"dry@example.com","label":"hot","created":true}]}'
 WHERE tool_name = 'crm.get';
