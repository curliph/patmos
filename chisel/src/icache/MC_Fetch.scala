/*
   Copyright 2013 Technical University of Denmark, DTU Compute. 
   All rights reserved.
   
   This file is part of the time-predictable VLIW processor Patmos.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are met:

      1. Redistributions of source code must retain the above copyright notice,
         this list of conditions and the following disclaimer.

      2. Redistributions in binary form must reproduce the above copyright
         notice, this list of conditions and the following disclaimer in the
         documentation and/or other materials provided with the distribution.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER ``AS IS'' AND ANY EXPRESS
   OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
   OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN
   NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY
   DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
   (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
   ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
   (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
   THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

   The views and conclusions contained in the software and documentation are
   those of the authors and should not be interpreted as representing official
   policies, either expressed or implied, of the copyright holder.
 */

/*
 * Fetch stage of Patmos.
 * 
 * Author: Martin Schoeberl (martin@jopdesign.com)
 * 
 */

package mcache

import Chisel._
import Node._

import MConstants._

/*
 connections for Fetch-Stage copied from connections.scala
 */
class FeDec() extends Bundle() {
  val instr_a = Bits(width = 32)
  val instr_b = Bits(width = 32)
  val b_valid = Bool() // not yet used
  val pc = UFix(width = PC_SIZE)
}
class ExFe() extends Bundle() {
  val doBranch = Bool()
  val branchPc = UFix(width = PC_SIZE)
}
class FeMem() extends Bundle() {
  val pc = UFix(width = PC_SIZE)
}
class MemFe() extends Bundle() {
  val doCallRet = Bool()
  val callRetPc = UFix(width = PC_SIZE)  
  // for ISPM write
  val store = Bool()
  val addr = Bits(width = DATA_WIDTH)
  val data = Bits(width = DATA_WIDTH)
}
class MCFetchIO extends Bundle() {
  val fedec = new FeDec().asOutput
  // PC for returns
  val femem = new FeMem().asOutput
  //branch from EX
  val exfe = new ExFe().asInput
  //call from MEM
  val memfe = new MemFe().asInput
  val mcache_in = new MCacheIn().asOutput
  val mcache_out = new MCacheOut().asInput
}

/*
  extension to class Fetch.scala to connect with the method cache
 */
class MCFetch() extends Component {
  val io = new MCFetchIO()

  val pc = Reg(resetVal = UFix(0, PC_SIZE))
  val addr_even = Reg(resetVal = UFix(0, PC_SIZE - 1))
  val addr_odd = Reg(resetVal = UFix(1, PC_SIZE - 1))

  //val rom = Utility.readBin(fileName)
  // Split the ROM into two blocks for dual fetch
  //  val len = rom.length / 2
  //  val rom_a = Vec(len) { Bits(width = INSTR_WIDTH) }
  //  val rom_b = Vec(len) { Bits(width = INSTR_WIDTH) }
  //  for (i <- 0 until len) {
  //    rom_a(i) = rom(i * 2)
  //    rom_b(i) = rom(i * 2 + 1)
  //    val a:Bits = rom_a(i)
  //    val b:Bits = rom_b(i)
  //    println(i+" "+a.toUFix.litValue()+" "+b.toUFix.litValue())
  //  }
  //
  //  // addr_even and odd count in words. Shall this be optimized?
  //  val data_even: Bits = rom_a(addr_even(PC_SIZE-1, 1))
  //  val data_odd: Bits = rom_b(addr_odd(PC_SIZE-1, 1))
  // relay on the optimization to recognize that those addresses are always even and odd
  // TODO: maybe make it explicit

  val ena = io.mcache_out.hit

  val ispmSize = 4096 // in bytes
  val ispmAddrBits = log2Up(ispmSize / 4 / 2)
  val memEven = { Mem(ispmSize / 4 / 2, seqRead = true) { Bits(width = INSTR_WIDTH) } }
  val memOdd = { Mem(ispmSize / 4 / 2, seqRead = true) { Bits(width = INSTR_WIDTH) } }
  // write from EX - use registers - ignore stall, as reply does not hurt
  val selWrite = io.memfe.store & (io.memfe.addr(31, 21) === Bits(0x1))
  val wrEven = Reg(selWrite & (io.memfe.addr(2) === Bits(0)))
  val wrOdd = Reg(selWrite & (io.memfe.addr(2) === Bits(1)))
  val addrReg = Reg(io.memfe.addr)
  val dataReg = Reg(io.memfe.data)
  when(wrEven) { memEven(addrReg(ispmAddrBits + 3 - 1, 3)) := dataReg }
  when(wrOdd) { memOdd(addrReg(ispmAddrBits + 3 - 1, 3)) := dataReg }
  // This would not work with asynchronous reset as the address
  // registers are set on reset. However, chisel uses synchronous
  // reset, which 'just' generates some more logic. And it looks
  // like the synthesize tool is able to duplicate the register.
  val ispm_even = memEven(addr_even(ispmAddrBits, 1))
  val ispm_odd = memOdd(addr_odd(ispmAddrBits, 1))

  // read from ISPM mapped to address 0x200000
  val selIspm = pc(31, 21) === Bits(0x1)
  // ROM/ISPM Mux
  //val data_even = Mux(selIspm, ispm_even, rom(addr_even))
  //val data_odd = Mux(selIspm, ispm_odd, rom(addr_odd))
  val data_even = Mux(selIspm, ispm_even, io.mcache_out.instr_a)
  val data_odd = Mux(selIspm, ispm_odd, io.mcache_out.instr_b)

  val instr_a = Mux(pc(0) === Bits(0), data_even, data_odd)
  val instr_b = Mux(pc(0) === Bits(0), data_odd, data_even)

  val b_valid = instr_a(31) === Bits(1)
  val pc_cont = pc + Mux(b_valid, UFix(2), UFix(1))
  //val mcache_address_in = pc + Bits(2)
  val pc_next =
	Mux(io.memfe.doCallRet, io.memfe.callRetPc,
		Mux(io.exfe.doBranch, io.exfe.branchPc,
			pc_cont))

  val pc_inc = Mux(pc_next(0), pc_next + UFix(2), pc_next)
  when(ena) {
    addr_even := Cat(pc_inc(PC_SIZE - 1, 1), Bits(0)).toUFix
    addr_odd := Cat(pc_next(PC_SIZE - 1, 1), Bits(1)).toUFix
    pc := pc_next
  }

  io.fedec.pc := pc
  io.fedec.instr_a := instr_a
  io.fedec.instr_b := instr_b
  io.fedec.b_valid := b_valid // not used at the moment

  io.femem.pc := pc_cont

  //outputs to mcache
  io.mcache_in.address := pc_next //mcache_address_in //pc_next
  io.mcache_in.request := Bits(1) //not used at the momenent
}
