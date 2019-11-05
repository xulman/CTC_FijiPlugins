package de.mpicbg.ulman.ctc.Mastodon;

import java.awt.*;
import javax.swing.*;

import org.jhotdraw.samples.svg.gui.ProgressIndicator;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;

import org.mastodon.collection.RefCollections;
import org.mastodon.collection.RefList;
import org.mastodon.spatial.SpatioTemporalIndex;
import org.scijava.log.LogService;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.Parameter;

import net.imglib2.realtransform.AffineTransform3D;

import org.mastodon.revised.mamut.MamutAppModel;
import org.mastodon.revised.model.mamut.Spot;
import org.mastodon.revised.model.mamut.Link;
import org.mastodon.revised.model.mamut.Model;
import org.mastodon.revised.model.mamut.ModelGraph;

import de.mpicbg.ulman.ctc.Mastodon.util.ImgProviders;

@Plugin( type = Command.class, name = "CTC TRA content reviewer @ Mastodon" )
public class TRAreviewPlugin
extends DynamicCommand
{
	// ----------------- necessary internal references -----------------
	@Parameter
	private LogService logService;

	@Parameter(persist = false)
	private MamutAppModel appModel;

	// ----------------- where to read data in -----------------
	@Parameter(label = "Review from this time point:", min="0")
	Integer timeFrom;

	@Parameter(label = "Review till this time point:", min="0")
	Integer timeTill;

	// ----------------- what the issues look like -----------------
	@Parameter(label = "Sudden trajectory change is above this angle (deg):", min="0", max="180")
	float maxToleratedAngle = 180;

	// ----------------- where the issues are and how to navigate to them -----------------
	/** the list of "suspicious" spots */
	private RefList< Spot > problemList;

	/** the list of descriptions of the "suspicious" spots (why they are deemed suspicious) */
	private ArrayList< String > problemDesc;

	/** the currently investigated spot */
	private int currentProblemIdx = -1;

	private void navToProblem()
	{
		if (problemList == null || problemDesc == null) return;
		if (currentProblemIdx < 0) currentProblemIdx = 0;
		if (currentProblemIdx >= problemList.size()) currentProblemIdx = problemList.size()-1;

		problemList.get( currentProblemIdx, oSpot );
		appModel.getFocusModel().focusVertex(oSpot);
		appModel.getSelectionModel().clearSelection();
		appModel.getSelectionModel().setSelected(oSpot,true);
		appModel.getHighlightModel().highlightVertex(oSpot);

		if (pbar != null) pbar.setProgress(currentProblemIdx);
		if (pMsg != null) pMsg.setText( problemDesc.get(currentProblemIdx) );
	}

	private ProgressIndicator pbar = null;
	private JLabel pMsg = null;

	//shared "proxy" objects, allocated and released in run()
	private Spot nSpot,oSpot;
	private Link linkRef;

	// ----------------- how to read data in -----------------
	@Override
	public void run()
	{
		//TODO: provide the view choosing dialog
		final ImgProviders.ImgProvider imgSource
			= new ImgProviders.ImgProviderFromMastodon(appModel.getSharedBdvData().getSources().get(0).getSpimSource(),timeFrom);

		logService.info("Considering resolution: "+imgSource.getVoxelDimensions().dimension(0)
		               +" x "+imgSource.getVoxelDimensions().dimension(1)
		               +" x "+imgSource.getVoxelDimensions().dimension(2)
		               +" px/"+imgSource.getVoxelDimensions().unit());

		//define some shortcut variables
		final Model model = appModel.getModel();
		final ModelGraph modelGraph = model.getGraph();

		//debug report
		logService.info("Time points span is   : "+timeFrom+"-"+timeTill);

		//transformation used
		final AffineTransform3D coordTransImg2World = new AffineTransform3D();

		//some more dimensionality-based attributes
		final int inImgDims = imgSource.numDimensions();
		final int[] position = new int[inImgDims];

		//volume and squared lengths of one voxel along all axes
		final double[] resSqLen  = new double[inImgDims];
		imgSource.getVoxelDimensions().dimensions(resSqLen);
		double resArea   = resSqLen[0] * resSqLen[1]; //NB: lengths are yet before squaring
		double resVolume = 1;
		for (int i=0; i < inImgDims; ++i)
		{
			resVolume *= resSqLen[i];
			resSqLen[i] *= resSqLen[i];
		}


		//allocate the shared proxy objects
		linkRef = modelGraph.edgeRef();
		nSpot = modelGraph.vertices().createRef();
		oSpot = modelGraph.vertices().createRef();

		//release the shared proxy objects
		class MyWindowAdapter extends WindowAdapter
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				windowClosing( (JFrame)null );
			}

			public void windowClosing(final JFrame closeThisFrameToo)
			{
				modelGraph.vertices().releaseRef(oSpot);
				modelGraph.vertices().releaseRef(nSpot);
				modelGraph.releaseRef(linkRef);
				if (closeThisFrameToo != null) closeThisFrameToo.dispose();
				logService.info("Reviewing closed.");
			}
		}
		final MyWindowAdapter releaseRoutine = new MyWindowAdapter();


		//PROGRESS BAR stuff
		//  - will use it first to show progress of the detection of problematic cases
		//  - will use it after to show progress of the review process
		//populate the bar and show it
		final JFrame pbframe = new JFrame("CTC TRA Reviewer Progress Bar @ Mastodon");
		pbframe.setLayout(new BoxLayout(pbframe.getContentPane(), BoxLayout.Y_AXIS));

		pbar = new ProgressIndicator("Issues reviewed: ", "", 0, 1, false);
		pbframe.add(pbar);

		pMsg = new JLabel("");
		pMsg.setMinimumSize(new Dimension(290,50));
		pMsg.setHorizontalTextPosition(JLabel.LEFT);
		pbframe.add(pMsg);

		final JPanel pbtnPanel = new JPanel();
		pbtnPanel.setLayout(new BoxLayout(pbtnPanel, BoxLayout.X_AXIS));

		Button pbtn = new Button("Previous issue");
		pbtn.addActionListener( (action) -> { --currentProblemIdx; navToProblem(); } );
		pbtnPanel.add(pbtn);

		pbtn = new Button("Next issue");
		pbtn.addActionListener( (action) -> { ++currentProblemIdx; navToProblem(); } );
		pbtnPanel.add(pbtn);

		pbtn = new Button("Stop reviewing");
		pbtn.addActionListener( (action) -> releaseRoutine.windowClosing( pbframe ) );
		pbtnPanel.add(pbtn);
		pbframe.add(pbtnPanel);

		pbframe.addWindowListener(releaseRoutine);
		pbframe.setMinimumSize(new Dimension(300, 140));
		pbframe.pack();
		pbframe.setLocationByPlatform(true);
		pbframe.setVisible(true);
		//PROGRESS BAR stuff


		//create the problem list
		problemList = RefCollections.createRefList( appModel.getModel().getGraph().vertices(),1000);
		problemDesc = new ArrayList<>(1000);

		pbar.setMinimum(timeFrom);
		pbar.setMaximum(timeTill);

		final SpatioTemporalIndex< Spot > spots = model.getSpatioTemporalIndex();
		final RefList< Spot > rootsList = RefCollections.createRefList( appModel.getModel().getGraph().vertices(),1000);

		for (int timePoint = timeFrom; timePoint <= timeTill; ++timePoint)
		for ( final Spot spot : spots.getSpatialIndex( timePoint ) )
		{
			//find how many back-references (time-wise) this spot has
			int countBackwardLinks = 0;
			for (int n=0; n < spot.incomingEdges().size(); ++n)
			{
				spot.incomingEdges().get(n, linkRef).getSource( oSpot );
				if (oSpot.getTimepoint() < timePoint && oSpot.getTimepoint() >= timeFrom) ++countBackwardLinks;
			}
			for (int n=0; n < spot.outgoingEdges().size(); ++n)
			{
				spot.outgoingEdges().get(n, linkRef).getTarget( oSpot );
				if (oSpot.getTimepoint() < timePoint && oSpot.getTimepoint() >= timeFrom) ++countBackwardLinks;
			}
			if (countBackwardLinks != 1)
			{
				rootsList.add( spot);
				enlistProblemSpot( spot, "root or daughter with "+countBackwardLinks+" predecessors" );
			}

			pbar.setProgress(timePoint+1);
		}

		final double toDeg = 180.0 / 3.14159;
		final double axis[] = new double[3];

		final double vec1[] = new double[3];
		final double vec2[] = new double[3];
		final double vec3[] = new double[3];
		for (int n=0; n < rootsList.size(); ++n) {
			final Spot spot = rootsList.get(n);
			if (getLastFollower(spot, nSpot) != 1) break;
			if (getLastFollower(nSpot, oSpot) != 1) break;

			//so, we have a chain here: spot -> nSpot -> oSpot
			 spot.localize(vec1);
			nSpot.localize(vec2);
			oSpot.localize(vec3);

			//deltaPos: nSpot -> oSpot
			vec3[0] -= vec2[0]; vec3[1] -= vec2[1]; vec3[2] -= vec2[2];

			//deltaPos: spot -> nSpot
			vec2[0] -= vec1[0]; vec2[1] -= vec1[1]; vec2[2] -= vec1[2];

			//logService.info("1->2: "+printVector(vec2));
			//logService.info("2->3: "+printVector(vec3));

			double angle = getRotationAngleAndAxis(vec2, vec3, axis);
			if (angle*toDeg > maxToleratedAngle)
				enlistProblemSpot(oSpot, "3rd spot: angle "+(angle*toDeg)+" too much");

			//logService.info("rot params: "+(angle*toDeg)+" deg around "+printVector(axis));

			//DEBUG CHECK:
			vec1[0] = vec2[0]; vec1[1] = vec2[1]; vec1[2] = vec2[2];
			rotateVector(vec1, axis,angle);
			logService.info("check around spot "+spot.getLabel()+": test ang = "+getRotationAngle(vec1,vec3));

			while (getLastFollower(oSpot, nSpot) == 1)
			{
				//BTW: oSpot -> nSpot
				//last dir is: vec3
				//last rot params: axis, angle

				//last dir is also vec2
				vec2[0] = vec3[0]; vec2[1] = vec3[1]; vec2[2] = vec3[2];

				//new dir is vec3
				oSpot.localize(vec1);
				nSpot.localize(vec3);
				//deltaPos: oSpot -> nSpot
				vec3[0] -= vec1[0]; vec3[1] -= vec1[1]; vec3[2] -= vec1[2];

				//last dir is also vec1
				vec1[0] = vec2[0]; vec1[1] = vec2[1]; vec1[2] = vec2[2];

				//predict relative direction of the next nSpot, and compare to the actual one
				rotateVector(vec1, axis,angle);
				angle = getRotationAngle(vec1, vec3);
				if (angle*toDeg > maxToleratedAngle)
					enlistProblemSpot(nSpot, "angle "+(angle*toDeg)+" too much");

				//update the rot params, and move to the next spot
				angle = getRotationAngleAndAxis(vec2, vec3, axis);
				oSpot.refTo( nSpot );
			}
		}

		if (problemList.size() > 0)
		{
			//init the "dialog"
			logService.info("Detected "+problemList.size()+" possible problems.");
			pbar.setMinimum(0);
			pbar.setMaximum(problemList.size()-1);
			currentProblemIdx = 0;
			navToProblem();
		}
		else
		{
			//just clean up and quit
			releaseRoutine.windowClosing( pbframe );
			logService.info("No problems detected, done.");
		}
	}

	private void enlistProblemSpot(final Spot spot, final String reason)
	{
		problemList.add( spot );
		problemDesc.add( reason );
	}

	/** returns the number of detected followers and returns
	    the last visited one (if there was at least one) */
	int getLastFollower(final Spot from, final Spot retFollower)
	{
		int followers = 0;
		for (int n=0; n < from.incomingEdges().size(); ++n)
		{
			from.incomingEdges().get(n, linkRef).getSource( retFollower );
			if (retFollower.getTimepoint() > from.getTimepoint() && retFollower.getTimepoint() <= timeTill) ++followers;
		}
		for (int n=0; n < from.outgoingEdges().size(); ++n)
		{
			from.outgoingEdges().get(n, linkRef).getTarget( retFollower );
			if (retFollower.getTimepoint() > from.getTimepoint() && retFollower.getTimepoint() <= timeTill) ++followers;
		}
		return followers;
	}


	/** returns angle (in radians) and rotAxis that would transform the fromVec to toVec */
	double getRotationAngleAndAxis(final double[] fromVec, final double[] toVec, final double[] rotAxis)
	{
		//rotation axis
		rotAxis[0] = fromVec[1]*toVec[2] - fromVec[2]*toVec[1];
		rotAxis[1] = fromVec[2]*toVec[0] - fromVec[0]*toVec[2];
		rotAxis[2] = fromVec[0]*toVec[1] - fromVec[1]*toVec[0];

		//normalized rotation axis
		final double rotAxisLen = Math.sqrt(rotAxis[0]*rotAxis[0] + rotAxis[1]*rotAxis[1] + rotAxis[2]*rotAxis[2]);
		rotAxis[0] /= rotAxisLen;
		rotAxis[1] /= rotAxisLen;
		rotAxis[2] /= rotAxisLen;

		//rotation angle
		return getRotationAngle(fromVec,toVec);
	}

	double getRotationAngle(final double[] fromVec, final double[] toVec)
	{
		double dotProd = fromVec[0]*toVec[0] + fromVec[1]*toVec[1] + fromVec[2]*toVec[2];
		dotProd /= Math.sqrt(fromVec[0]*fromVec[0] + fromVec[1]*fromVec[1] + fromVec[2]*fromVec[2]);
		dotProd /= Math.sqrt(  toVec[0]*  toVec[0] +   toVec[1]*  toVec[1] +   toVec[2]*  toVec[2]);
		return Math.acos(dotProd);
	}

	void rotateVector(final double[] vec, final double[] rotAxis, final double rotAng)
	{
		if (Math.abs(rotAng) < 0.05) //smaller than 3 deg
		{
			rotMatrix[0] = 1; rotMatrix[1] = 0; rotMatrix[2] = 0;
			rotMatrix[3] = 0; rotMatrix[4] = 1; rotMatrix[5] = 0;
			rotMatrix[6] = 0; rotMatrix[7] = 0; rotMatrix[8] = 1;
		}
		else
		{
			//quaternion params
			final double rAng = rotAng / 2.0;
			final double q0 = Math.cos(rAng);
			final double q1 = Math.sin(rAng) * rotAxis[0];
			final double q2 = Math.sin(rAng) * rotAxis[1];
			final double q3 = Math.sin(rAng) * rotAxis[2];

			//rotation matrix from the quaternion
			//        row col
			rotMatrix[0*3 +0] = q0*q0 + q1*q1 - q2*q2 - q3*q3;
			rotMatrix[0*3 +1] = 2 * (q1*q2 - q0*q3);
			rotMatrix[0*3 +2] = 2 * (q1*q3 + q0*q2);

			//this is the 2nd row of the matrix...
			rotMatrix[1*3 +0] = 2 * (q2*q1 + q0*q3);
			rotMatrix[1*3 +1] = q0*q0 - q1*q1 + q2*q2 - q3*q3;
			rotMatrix[1*3 +2] = 2 * (q2*q3 - q0*q1);

			rotMatrix[2*3 +0] = 2 * (q3*q1 - q0*q2);
			rotMatrix[2*3 +1] = 2 * (q3*q2 + q0*q1);
			rotMatrix[2*3 +2] = q0*q0 - q1*q1 - q2*q2 + q3*q3;
		}

		//rotate the input vector
		double x = rotMatrix[0]*vec[0] + rotMatrix[1]*vec[1] + rotMatrix[2]*vec[2];
		double y = rotMatrix[3]*vec[0] + rotMatrix[4]*vec[1] + rotMatrix[5]*vec[2];
		  vec[2] = rotMatrix[6]*vec[0] + rotMatrix[7]*vec[1] + rotMatrix[8]*vec[2];
		  vec[0] = x;
		  vec[1] = y;
	}
	private double[] rotMatrix = new double[9];

	private String printVector(final double[] vec)
	{
		return new String("("+vec[0]+","+vec[1]+","+vec[2]+")");
	}
}