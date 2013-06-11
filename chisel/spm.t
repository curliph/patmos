# A simple linker script to put code and data into the SPMs
SECTIONS
{
  . = SEGMENT_START(".rodata", 0x5000);
  .rodata : { *(.rodata .rodata.*) }
  .data : { *(.data) }
  .bss : { *(.bss) }

  . = ALIGN(8);
  _end = .; PROVIDE (end = .);

  . = SEGMENT_START(".text", 0x800000);
  .text : { *(.text .text.*) }
}
