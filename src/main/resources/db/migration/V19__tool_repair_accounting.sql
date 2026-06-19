ALTER TABLE gateway_request ADD COLUMN tool_repair_attempted boolean NOT NULL DEFAULT false,
 ADD COLUMN tool_repair_recovered boolean NOT NULL DEFAULT false,
 ADD COLUMN attempt_input_tokens bigint,ADD COLUMN attempt_output_tokens bigint;
