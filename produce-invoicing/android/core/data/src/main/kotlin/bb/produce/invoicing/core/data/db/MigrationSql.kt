package bb.produce.invoicing.core.data.db

/**
 * v1 → v2 DDL, generated from `schemas/.../1.json` and `2.json` so it matches Room's expected
 * schema exactly (verified by MigrationTest). Do not edit by hand; regenerate if v2 entities change
 * before release — after release, write a new migration instead.
 */
internal val MIGRATION_1_2_SQL: List<String> = listOf(
    "ALTER TABLE `business` ADD COLUMN `negative_stock_policy` TEXT NOT NULL DEFAULT 'BLOCK'",
    "CREATE TABLE IF NOT EXISTS `location` (`id` TEXT NOT NULL, `business_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`business_id`) REFERENCES `business`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_location_business_id` ON `location` (`business_id`)",
    "CREATE TABLE IF NOT EXISTS `shift` (`id` TEXT NOT NULL, `business_id` TEXT NOT NULL, `driver_user_id` TEXT NOT NULL, `device_id` TEXT NOT NULL, `vehicle_location_id` TEXT NOT NULL, `local_date` TEXT NOT NULL, `status` TEXT NOT NULL, `status_reason` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`business_id`) REFERENCES `business`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`vehicle_location_id`) REFERENCES `location`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_shift_business_id` ON `shift` (`business_id`)",
    "CREATE INDEX IF NOT EXISTS `index_shift_vehicle_location_id` ON `shift` (`vehicle_location_id`)",
    "CREATE INDEX IF NOT EXISTS `index_shift_driver_user_id_status` ON `shift` (`driver_user_id`, `status`)",
    "CREATE TABLE IF NOT EXISTS `stock_conversion` (`product_id` TEXT NOT NULL, `sell_unit` TEXT NOT NULL, `base_unit` TEXT NOT NULL, `factor` TEXT NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`product_id`, `sell_unit`), FOREIGN KEY(`product_id`) REFERENCES `product`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE TABLE IF NOT EXISTS `stock_movement` (`id` TEXT NOT NULL, `business_id` TEXT NOT NULL, `shift_id` TEXT NOT NULL, `location_id` TEXT NOT NULL, `product_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `bucket` TEXT NOT NULL, `quantity` TEXT NOT NULL, `unit` TEXT NOT NULL, `sale_invoice_id` TEXT, `sale_line_id` TEXT, `conversion_version` INTEGER, `reverses_movement_id` TEXT, `reason` TEXT, `actor_id` TEXT NOT NULL, `actor_role` TEXT NOT NULL, `device_id` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`shift_id`) REFERENCES `shift`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`location_id`) REFERENCES `location`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`sale_invoice_id`) REFERENCES `invoice`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stock_movement_sale_invoice_id_sale_line_id` ON `stock_movement` (`sale_invoice_id`, `sale_line_id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stock_movement_reverses_movement_id` ON `stock_movement` (`reverses_movement_id`)",
    "CREATE INDEX IF NOT EXISTS `index_stock_movement_location_id_product_id` ON `stock_movement` (`location_id`, `product_id`)",
    "CREATE INDEX IF NOT EXISTS `index_stock_movement_shift_id` ON `stock_movement` (`shift_id`)",
    "CREATE TABLE IF NOT EXISTS `payment_event` (`id` TEXT NOT NULL, `invoice_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `method` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, `recorded_by` TEXT NOT NULL, `device_id` TEXT NOT NULL, `at` INTEGER NOT NULL, `external_reference` TEXT, `reverses_event_id` TEXT, `reason` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`invoice_id`) REFERENCES `invoice`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_payment_event_invoice_id` ON `payment_event` (`invoice_id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_event_reverses_event_id` ON `payment_event` (`reverses_event_id`)",
    "CREATE TABLE IF NOT EXISTS `return_intake` (`id` TEXT NOT NULL, `business_id` TEXT NOT NULL, `shift_id` TEXT NOT NULL, `invoice_id` TEXT, `invoice_line_id` TEXT, `product_id` TEXT NOT NULL, `quantity` TEXT NOT NULL, `unit` TEXT NOT NULL, `condition` TEXT NOT NULL, `reason` TEXT NOT NULL, `disposition` TEXT NOT NULL, `recorded_by` TEXT NOT NULL, `device_id` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`shift_id`) REFERENCES `shift`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`invoice_id`) REFERENCES `invoice`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_return_intake_shift_id` ON `return_intake` (`shift_id`)",
    "CREATE INDEX IF NOT EXISTS `index_return_intake_invoice_id` ON `return_intake` (`invoice_id`)",
    "CREATE TABLE IF NOT EXISTS `stock_count` (`id` TEXT NOT NULL, `shift_id` TEXT NOT NULL, `product_id` TEXT NOT NULL, `counted` TEXT NOT NULL, `expected` TEXT NOT NULL, `variance` TEXT NOT NULL, `unit` TEXT NOT NULL, `reason` TEXT, `counted_by` TEXT NOT NULL, `device_id` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`shift_id`) REFERENCES `shift`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_stock_count_shift_id_product_id` ON `stock_count` (`shift_id`, `product_id`)",
    "CREATE TABLE IF NOT EXISTS `printer_profile` (`id` TEXT NOT NULL, `manufacturer` TEXT NOT NULL, `model` TEXT NOT NULL, `firmware` TEXT, `paper_width_mm` INTEGER NOT NULL, `printable_dots` INTEGER NOT NULL, `chars_per_line` INTEGER NOT NULL, `code_page` TEXT NOT NULL, `transport` TEXT NOT NULL, `supports_cut` INTEGER NOT NULL, `supports_status_query` INTEGER NOT NULL, `certification_ref` TEXT, `certified_at` INTEGER, `tested_on` TEXT, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `print_attempt` (`id` TEXT NOT NULL, `invoice_id` TEXT NOT NULL, `printer_profile_id` TEXT NOT NULL, `transport` TEXT NOT NULL, `copy_number` INTEGER NOT NULL, `operator_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `status` TEXT NOT NULL, `reprint_reason` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`invoice_id`) REFERENCES `invoice`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`printer_profile_id`) REFERENCES `printer_profile`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_print_attempt_invoice_id` ON `print_attempt` (`invoice_id`)",
    "CREATE INDEX IF NOT EXISTS `index_print_attempt_printer_profile_id` ON `print_attempt` (`printer_profile_id`)",
)
