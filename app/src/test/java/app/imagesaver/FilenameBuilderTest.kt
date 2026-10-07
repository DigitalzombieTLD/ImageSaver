 @Test fun withGps() {
-    val name = FilenameBuilder.build(7, time, GpsCoordinates(48.1234567, -11.5), "12,5", "hello", utc)
-    assertEquals("00007_2023-11-14_22-13-20_48.123457_-11.500000_12.5_hello.jpg", name)
+    val name = FilenameBuilder.build(7, time, GpsCoordinates(48.1234567, -11.5), "12,5", "2.5", utc)
+    assertEquals("00007_2023-11-14_22-13-20_48.123457_-11.500000_12.5_2.5.jpg", name)
 }
 
 @Test fun noGpsUsesZeroCoordinates() {
-    val name = FilenameBuilder.build(1, time, null, "3", "x", utc)
-    assertEquals("00001_2023-11-14_22-13-20_0.0_0.0_3_x.jpg", name)
+    val name = FilenameBuilder.build(1, time, null, "3", "", utc)
+    assertEquals("00001_2023-11-14_22-13-20_0.0_0.0_3_X.jpg", name)
     assertFalse(name.contains("no_gps"))
 }
