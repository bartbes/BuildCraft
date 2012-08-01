/**
 * Copyright (c) SpaceToad, 2011
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */

package buildcraft.transport;

import java.util.LinkedList;

import buildcraft.BuildCraftCore;
import buildcraft.BuildCraftTransport;
import buildcraft.mod_BuildCraftCore;
import buildcraft.api.APIProxy;
import buildcraft.api.core.Orientations;
import buildcraft.api.core.Position;
import buildcraft.api.core.SafeTimeTracker;
import buildcraft.api.gates.IOverrideDefaultTriggers;
import buildcraft.api.gates.ITrigger;
import buildcraft.api.gates.Trigger;
import buildcraft.api.liquids.ILiquidTank;
import buildcraft.api.liquids.ITankContainer;
import buildcraft.api.liquids.LiquidStack;
import buildcraft.api.power.IPowerProvider;
import buildcraft.api.power.IPowerReceptor;
import buildcraft.api.transport.IPipe;
import buildcraft.api.transport.IPipeConnection;
import buildcraft.api.transport.IPipeEntry;
import buildcraft.api.transport.IPipeTile;
import buildcraft.api.transport.IPipedItem;
import buildcraft.api.transport.IPipe.WireColor;
import buildcraft.core.CoreProxy;
import buildcraft.core.DefaultProps;
import buildcraft.core.IDropControlInventory;
import buildcraft.core.ITileBufferHolder;
import buildcraft.core.TileBuffer;
import buildcraft.core.Utils;
import buildcraft.core.network.IndexInPayload;
import buildcraft.core.network.PacketPayload;
import buildcraft.core.network.PacketPipeDescription;
import buildcraft.core.network.PacketTileUpdate;
import buildcraft.core.network.PacketUpdate;
import buildcraft.core.network.TileNetworkData;
import buildcraft.transport.network.PipeRenderStatePacket;

import net.minecraft.src.Block;
import net.minecraft.src.ItemStack;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.Packet;
import net.minecraft.src.TileEntity;

public class TileGenericPipe extends TileEntity implements IPowerReceptor, ITankContainer, IPipeEntry,
		IPipeTile, IOverrideDefaultTriggers, ITileBufferHolder, IPipeConnection, IDropControlInventory, IPipeRenderState {
	
	private PipeRenderState renderState = new PipeRenderState();

	public TileBuffer[] tileBuffer;
	public boolean[] pipeConnectionsBuffer = new boolean[6];

	public SafeTimeTracker networkSyncTracker = new SafeTimeTracker();

	public Pipe pipe;
	private boolean blockNeighborChange = false;
	private boolean refreshRenderState = false;
	private boolean pipeBound = false;
	
	private int[] facadeBlocks = new int[Orientations.dirs().length];
	private int[] facadeMeta = new int[Orientations.dirs().length];
	
	//Store the pipe key to prevent losing pipes when a user forgets to include an addon
	int key; 

	@TileNetworkData
	public int pipeId = -1;

	public TileGenericPipe() {

	}

	@Override
	public void writeToNBT(NBTTagCompound nbttagcompound) {
		super.writeToNBT(nbttagcompound);

		if (pipe != null) {
			nbttagcompound.setInteger("pipeId", pipe.itemID);
			pipe.writeToNBT(nbttagcompound);
		} else
			nbttagcompound.setInteger("pipeId", key);
		
		for (int i = 0; i < Orientations.dirs().length; i++){
			nbttagcompound.setInteger("facadeBlocks[" + i + "]", facadeBlocks[i]);
			nbttagcompound.setInteger("facadeMeta[" + i + "]", facadeMeta[i]);
		}

	}

	@Override
	public void readFromNBT(NBTTagCompound nbttagcompound) {
		super.readFromNBT(nbttagcompound);
		

		key = nbttagcompound.getInteger("pipeId");
		pipe = BlockGenericPipe.createPipe(key);

		if (pipe != null) {
			pipe.readFromNBT(nbttagcompound);
		}
		
		for (int i = 0; i < Orientations.dirs().length; i++){
			facadeBlocks[i] = nbttagcompound.getInteger("facadeBlocks[" + i + "]");
			facadeMeta[i] = nbttagcompound.getInteger("facadeMeta[" + i + "]");
		}

	}

	@Override
	public void invalidate() {
		initialized = false;
		if (pipe != null){
			pipe.invalidate();
		}
		super.invalidate();

//		if (BlockGenericPipe.isValid(pipe))
//			BlockGenericPipe.removePipe(pipe);
	}

	@Override
	public void validate() {
		super.validate();
		bindPipe();
		if (pipe != null) {
			pipe.validate();
	}
	}

	public boolean initialized = false;

	@Override
	public void updateEntity() {
		
		if (pipe == null)
			return;
		
		if (!initialized)
			initialize(pipe);

		if (!BlockGenericPipe.isValid(pipe))
			return;

		if (blockNeighborChange) {
			
			computeConnections();
			pipe.onNeighborBlockChange(0);
			blockNeighborChange = false;
			refreshRenderState = true;
		}
		
		if (refreshRenderState){
			refreshRenderState();
			refreshRenderState = false;
		}

		IPowerProvider provider = getPowerProvider();

		if (provider != null)
			provider.update(this);

		if (pipe != null)
			pipe.updateEntity();
	}

	//PRECONDITION: worldObj must not be null
	private void refreshRenderState() {
		
		//Only done on server/SSP
		if (worldObj.isRemote) return;
		
		// Pipe connections;
		for(Orientations o : Orientations.dirs()){
			renderState.pipeConnectionMatrix.setConnected(o, this.pipeConnectionsBuffer[o.ordinal()]);
		}
		
		// Pipe Textures
		renderState.setTextureFile(pipe.getTextureFile());
		for(Orientations o: Orientations.values()){
			renderState.textureMatrix.setTextureIndex(o, pipe.getTextureIndex(o));
		}
		
		// WireState
		for (IPipe.WireColor color : IPipe.WireColor.values()){
			renderState.wireMatrix.setWire(color, pipe.wireSet[color.ordinal()]);
			for (Orientations direction : Orientations.dirs()){
				renderState.wireMatrix.setWireConnected(color, direction, pipe.isWireConnectedTo(this.getTile(direction), color));
			}
		}
		
		// Wire Textures
			
		if (pipe.wireSet[IPipe.WireColor.Red.ordinal()]) {
			renderState.wireMatrix.setTextureIndex(WireColor.Red, pipe.signalStrength[IPipe.WireColor.Red.ordinal()] > 0 ? 6 : 5);
		} else {
			renderState.wireMatrix.setTextureIndex(WireColor.Red, 0);
		}
		
		if (pipe.wireSet[IPipe.WireColor.Blue.ordinal()]) {
			renderState.wireMatrix.setTextureIndex(WireColor.Blue, pipe.signalStrength[IPipe.WireColor.Blue.ordinal()] > 0 ? 8 : 7);
		} else {
			renderState.wireMatrix.setTextureIndex(WireColor.Blue, 0);
		}
		
		if (pipe.wireSet[IPipe.WireColor.Green.ordinal()]) {
			renderState.wireMatrix.setTextureIndex(WireColor.Green, pipe.signalStrength[IPipe.WireColor.Green.ordinal()] > 0 ? 10 : 9);
		} else {
			renderState.wireMatrix.setTextureIndex(WireColor.Green, 0);
		}
		
		if (pipe.wireSet[IPipe.WireColor.Yellow.ordinal()]) {
			renderState.wireMatrix.setTextureIndex(WireColor.Yellow, pipe.signalStrength[IPipe.WireColor.Yellow.ordinal()] > 0 ? 12 : 11);
		} else {
			renderState.wireMatrix.setTextureIndex(WireColor.Yellow, 0);
		}
		
		// Gate Textures
		renderState.setHasGate(pipe.hasGate());
		renderState.setGateTexture(!pipe.hasGate()?0:pipe.gate.getTexture(pipe.isGateActive()));

		// Facades
		for (Orientations direction:Orientations.dirs()){
			int blockId = this.facadeBlocks[direction.ordinal()];
			renderState.facadeMatrix.setConnected(direction, blockId != 0 && Block.blocksList[blockId] != null);
			if (Block.blocksList[blockId] != null){
				Block block = Block.blocksList[blockId];
				renderState.facadeMatrix.setTextureFile(direction, block.getTextureFile());
				renderState.facadeMatrix.setTextureIndex(direction, block.getBlockTextureFromSideAndMetadata(direction.ordinal(), this.facadeMeta[direction.ordinal()]));
			}
		}
		
		
		if (renderState.isDirty()){
			//worldObj.markBlockAsNeedsUpdate(this.xCoord, this.yCoord, this.zCoord);
			worldObj.markBlockNeedsUpdate(this.xCoord, this.yCoord, this.zCoord);
			renderState.clean();
		}
		
	}

	public void initialize(Pipe pipe) {
		
		this.pipe = pipe;
		
		tileBuffer = new TileBuffer[6];

		for (Orientations o : Orientations.dirs()) {
			Position pos = new Position(xCoord, yCoord, zCoord, o);
			pos.moveForwards(1.0);

			tileBuffer[o.ordinal()] = new TileBuffer();
			tileBuffer[o.ordinal()].initialize(worldObj, (int) pos.x, (int) pos.y, (int) pos.z);
		}

		for (Orientations o : Orientations.dirs()) {
			TileEntity tile = getTile(o);

			if (tile instanceof ITileBufferHolder)
				((ITileBufferHolder) tile).blockCreated(o, BuildCraftTransport.genericPipeBlock.blockID, this);
		}

		bindPipe();

		computeConnections();
		scheduleRenderUpdate();

		if (pipe != null)
			pipe.initialize();
		
		initialized = true;
	}

	private void bindPipe() {
		
		if (!pipeBound && pipe != null) {
			
			pipe.setTile(this);
			
			pipeId = pipe.itemID;
			pipeBound = true;
		}
	}
	
	@Override
	public IPipe getPipe() {
		return pipe;
	}

	@Override
	public boolean isInitialized() {
		return initialized;
	}

	@Override
	public void setPowerProvider(IPowerProvider provider) {
		if (BlockGenericPipe.isValid(pipe) && pipe instanceof IPowerReceptor)
			((IPowerReceptor) pipe).setPowerProvider(provider);

	}

	@Override
	public IPowerProvider getPowerProvider() {
		if (BlockGenericPipe.isValid(pipe) && pipe instanceof IPowerReceptor)
			return ((IPowerReceptor) pipe).getPowerProvider();
		else
			return null;
	}

	@Override
	public void doWork() {
		if (BlockGenericPipe.isValid(pipe) && pipe instanceof IPowerReceptor)
			((IPowerReceptor) pipe).doWork();
	}

	public void scheduleNeighborChange() {
		blockNeighborChange = true;
	}

	@Override
	public void entityEntering(IPipedItem item, Orientations orientation) {
		if (BlockGenericPipe.isValid(pipe))
			pipe.transport.entityEntering(item, orientation);
	}

	@Override
	public boolean acceptItems() {
		if (BlockGenericPipe.isValid(pipe))
			return pipe.transport.acceptItems();
		else
			return false;
	}

	public void handleDescriptionPacket(PipeRenderStatePacket packet) {
		if (worldObj.isRemote){
			if (pipe == null && packet.getPipeId() != 0){
				initialize(BlockGenericPipe.createPipe(packet.getPipeId()));
				if (packet.gateKind != -1) {
					ItemStack stack = new ItemStack(packet.gateId, 0, packet.gateKind);
					pipe.gate = new GateVanilla(pipe, stack);
					pipe.gate.kind = Gate.GateKind.values()[packet.gateKind];
				}
			}
			renderState = packet.getRenderState();
			worldObj.markBlockAsNeedsUpdate(xCoord, yCoord, zCoord);
		}
		
		
		
		return;	
	}

	public Packet getDescriptionPacket() {
		bindPipe();
		int gateId, gateKind;
		if (pipe.gate == null) {
			gateId = 0;
			gateKind = -1;
		} else {
			gateId = BuildCraftTransport.pipeGate.shiftedIndex;
			if (pipe.gate instanceof GateVanilla &&
					((GateVanilla) pipe.gate).hasPulser())
				gateId = BuildCraftTransport.pipeGateAutarchic.shiftedIndex;
			gateKind = pipe.gate.kind.ordinal();
		}
		PipeRenderStatePacket packet = new PipeRenderStatePacket(this.renderState, this.pipeId, xCoord, yCoord, zCoord, gateId, gateKind);
		return packet.getPacket();
	}

	@Override
	public int powerRequest() {
		return getPowerProvider().getMaxEnergyReceived();
	}

	@Override
	public LinkedList<ITrigger> getTriggers() {
		LinkedList<ITrigger> result = new LinkedList<ITrigger>();

		if (BlockGenericPipe.isFullyDefined(pipe) && pipe.hasGate()) {
			result.add(BuildCraftCore.triggerRedstoneActive);
			result.add(BuildCraftCore.triggerRedstoneInactive);
		}

		return result;
	}

	@Override
	public void blockRemoved(Orientations from) {
		// TODO Auto-generated method stub

	}

	@Override
	public void blockCreated(Orientations from, int blockID, TileEntity tile) {
		if (tileBuffer != null)
			tileBuffer[from.reverse().ordinal()].set(blockID, tile);
	}

	@Override
	public int getBlockId(Orientations to) {
		if (tileBuffer != null)
			return tileBuffer[to.ordinal()].getBlockID();
		else
			return 0;
	}

	@Override
	public TileEntity getTile(Orientations to) {
		if (tileBuffer != null)
			return tileBuffer[to.ordinal()].getTile();
		else
			return null;
	}

	public boolean isPipeConnected(TileEntity with) {
		Pipe pipe1 = pipe;
		Pipe pipe2 = null;

		if (with instanceof TileGenericPipe)
			pipe2 = ((TileGenericPipe) with).pipe;

		if (!BlockGenericPipe.isValid(pipe1))
			return false;

		if (BlockGenericPipe.isValid(pipe2) && !pipe1.transport.getClass().isAssignableFrom(pipe2.transport.getClass())
				&& !pipe1.transport.allowsConnect(pipe2.transport))
			return false;

		if (pipe2 != null && !( pipe2.isPipeConnected(this)))
			return false;

		return pipe1 != null ? pipe1.isPipeConnected(with) : false;
	}

	private void computeConnections() {
		if (tileBuffer != null) {
			boolean[] oldConnections = pipeConnectionsBuffer;
			pipeConnectionsBuffer = new boolean[6];

			for (int i = 0; i < tileBuffer.length; ++i) {
				TileBuffer t = tileBuffer[i];
				t.refresh();

				if (t.getTile() != null) {
					pipeConnectionsBuffer[i] = isPipeConnected(t.getTile());

					if (t.getTile() instanceof TileGenericPipe) {
						TileGenericPipe pipe = (TileGenericPipe) t.getTile();
						pipe.pipeConnectionsBuffer[Orientations.values()[i].reverse().ordinal()] = pipeConnectionsBuffer[i];
					}
				}
			}

			for (int i = 0; i < tileBuffer.length; ++i)
				if (oldConnections[i] != pipeConnectionsBuffer[i]) {
					Position pos = new Position(xCoord, yCoord, zCoord, Orientations.values()[i]);
					pos.moveForwards(1.0);
					scheduleRenderUpdate();
				}
		}
	}

	@Override
	public boolean isPipeConnected(Orientations with) {
		return pipeConnectionsBuffer[with.ordinal()];
	}

	@Override
	public boolean doDrop() {
		if (BlockGenericPipe.isValid(pipe))
			return pipe.doDrop();
		else
			return false;
	}
	
	@Override
	public void onChunkUnload() {
		if (pipe != null){
			pipe.onChunkUnload();
		}
	}

	
	/** ITankContainer implementation **/
	
	@Override
	public int fill(Orientations from, LiquidStack resource, boolean doFill) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof ITankContainer)
			return ((ITankContainer) pipe.transport).fill(from, resource, doFill);
		else
			return 0;
	}

	@Override
	public int fill(int tankIndex, LiquidStack resource, boolean doFill) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof ITankContainer)
			return ((ITankContainer) pipe.transport).fill(tankIndex, resource, doFill);
		else
			return 0;
	}

	@Override
	public LiquidStack drain(Orientations from, int maxDrain, boolean doDrain) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof ITankContainer)
			return ((ITankContainer) pipe.transport).drain(from, maxDrain, doDrain);
		else
			return null;
	}

	@Override
	public LiquidStack drain(int tankIndex, int maxDrain, boolean doDrain) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof ITankContainer)
			return ((ITankContainer) pipe.transport).drain(tankIndex, maxDrain, doDrain);
		else
			return null;
	}

	@Override
	public ILiquidTank[] getTanks() {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof ITankContainer)
			return ((ITankContainer) pipe.transport).getTanks();
		else
			return null;
	}
	
	
	public void scheduleRenderUpdate(){
		refreshRenderState = true;
	}
	
	public boolean addFacade(Orientations direction, int blockid, int meta){
		if (this.worldObj.isRemote) return false;
		if (this.facadeBlocks[direction.ordinal()] == blockid) return false;
		
		if (hasFacade(direction)){
			dropFacade(direction);
		}
		
		this.facadeBlocks[direction.ordinal()] = blockid;
		this.facadeMeta[direction.ordinal()] = meta;
		scheduleRenderUpdate();
		//refreshRenderState();
		return true;
	}
	
	public boolean hasFacade(Orientations direction){
		if (this.worldObj.isRemote) return false;
		return (this.facadeBlocks[direction.ordinal()] != 0);
	}
	
	public void dropFacade(Orientations direction){
		if (this.worldObj.isRemote) return;
		if (!hasFacade(direction)) return;
		Utils.dropItems(worldObj, new ItemStack(BuildCraftTransport.facadeItem, 1, ItemFacade.encode(this.facadeBlocks[direction.ordinal()], this.facadeMeta[direction.ordinal()])), this.xCoord, this.yCoord, this.zCoord);
		this.facadeBlocks[direction.ordinal()] = 0;
		this.facadeMeta[direction.ordinal()] = 0;
		scheduleRenderUpdate();
		//refreshRenderState();
	}
	
	/** IPipeRenderState implementation **/

	@Override
	public PipeRenderState getRenderState() {
		return renderState;
	}
}