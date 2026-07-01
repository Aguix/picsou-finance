-- V38: Add bank logo URL to account and requisition tables

ALTER TABLE account ADD COLUMN logo_url VARCHAR(500);
ALTER TABLE requisition ADD COLUMN logo_url VARCHAR(500);
