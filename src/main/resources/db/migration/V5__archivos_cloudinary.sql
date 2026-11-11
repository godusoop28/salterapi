-- Evidencias y archivos: almacenamiento externo opcional (Cloudinary). Los archivos existentes siguen en la base.
ALTER TABLE attachments ADD COLUMN storage       VARCHAR(20)  NOT NULL DEFAULT 'DB';
ALTER TABLE attachments ADD COLUMN storage_key   VARCHAR(300);
ALTER TABLE attachments ADD COLUMN resource_type VARCHAR(20);
ALTER TABLE attachments ADD COLUMN storage_version BIGINT;
ALTER TABLE attachments ALTER COLUMN data DROP NOT NULL;
ALTER TABLE attachments ADD CONSTRAINT ck_attachments_storage
    CHECK ((storage = 'DB' AND data IS NOT NULL) OR (storage = 'CLOUDINARY' AND storage_key IS NOT NULL));
